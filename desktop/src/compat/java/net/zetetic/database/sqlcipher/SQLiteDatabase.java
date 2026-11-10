package net.zetetic.database.sqlcipher;

import android.content.ContentValues;
import android.database.Cursor;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.sqlite.mc.SQLiteMCConfig;
import org.sqlite.mc.SQLiteMCSqlCipherConfig;

/**
 * SQLCipher for desktop builds, with the API of SQLCipher for Android that the shared storage code
 * uses. Underneath is SQLite3 Multiple Ciphers through JDBC, set to SQLCipher 4's file format, so a
 * database or backup written on a phone opens here and the other way round.
 *
 * One connection, shared by every thread. Like Android's SQLiteDatabase, a transaction belongs to
 * the thread that started it: other threads wait until it ends instead of writing into it, and a
 * rolled-back transaction can't take another thread's writes with it. Query results are read in
 * full before the call returns, so a cursor never holds the connection.
 */
public final class SQLiteDatabase implements AutoCloseable {

    public static final int CONFLICT_NONE = 0;
    public static final int CONFLICT_ROLLBACK = 1;
    public static final int CONFLICT_ABORT = 2;
    public static final int CONFLICT_FAIL = 3;
    public static final int CONFLICT_IGNORE = 4;
    public static final int CONFLICT_REPLACE = 5;

    private static final String[] CONFLICT_CLAUSES = {"", " OR ROLLBACK", " OR ABORT", " OR FAIL", " OR IGNORE", " OR REPLACE"};
    private static final Pattern EXPORT = Pattern.compile("^\\s*SELECT\\s+sqlcipher_export\\s*\\(\\s*'([A-Za-z_][A-Za-z0-9_]*)'\\s*\\)\\s*;?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_TABLE = Pattern.compile("^\\s*CREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_INDEX = Pattern.compile("^\\s*CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(IF\\s+NOT\\s+EXISTS\\s+)?", Pattern.CASE_INSENSITIVE);
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final Connection connection;
    private final String path;
    private final ReentrantLock lock = new ReentrantLock();
    private final java.util.ArrayDeque<boolean[]> transactionLevels = new java.util.ArrayDeque<>();
    private int transactionDepth = 0;
    private boolean transactionFailed = false;
    private volatile boolean closed = false;

    private SQLiteDatabase(Connection connection, String path) {
        this.connection = connection;
        this.path = path;
    }

    /**
     * Opens or creates [file] with a raw 32-byte key, the equivalent of SQLCipher's x'...' key
     * syntax. Throws if the key doesn't open an existing file.
     */
    public static SQLiteDatabase openWithRawKey(File file, byte[] rawKey) throws SQLException {
        if (rawKey == null || rawKey.length != 32) throw new IllegalArgumentException("Raw SQLCipher keys are 32 bytes");
        SQLiteMCConfig.Builder builder = SQLiteMCSqlCipherConfig.getV4Defaults().withRawUnsaltedKey(rawKey.clone());
        SQLiteMCConfig config = builder.build();
        config.setBusyTimeout(30_000);
        Connection connection = config.createConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA temp_store = MEMORY");
                statement.execute("PRAGMA secure_delete = ON");
                try (ResultSet check = statement.executeQuery("SELECT count(*) FROM sqlite_master")) {
                    check.next();
                }
            }
            return new SQLiteDatabase(connection, file.getAbsolutePath());
        } catch (SQLException | RuntimeException e) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
            throw e;
        }
    }

    public String getPath() {
        return path;
    }

    public boolean isOpen() {
        return !closed;
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            try {
                if (transactionDepth > 0) {
                    try (Statement s = connection.createStatement()) {
                        s.execute("ROLLBACK");
                    } catch (SQLException ignored) {
                    }
                }
                connection.close();
            } catch (SQLException ignored) {
            }
        } finally {
            transactionDepth = 0;
            transactionLevels.clear();
            while (lock.getHoldCount() > 0) lock.unlock();
        }
    }

    public int getVersion() {
        Cursor c = rawQuery("PRAGMA user_version", null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public void setVersion(int version) {
        execSQL("PRAGMA user_version = " + version);
    }

    public void beginTransaction() {
        lock.lock();
        boolean begun = false;
        try {
            ensureOpen();
            if (transactionDepth == 0) {
                try (Statement s = connection.createStatement()) {
                    s.execute("BEGIN IMMEDIATE");
                }
                transactionFailed = false;
            }
            transactionLevels.push(new boolean[] {false});
            transactionDepth++;
            begun = true;
        } catch (SQLException e) {
            throw new SQLiteException(e);
        } finally {
            if (!begun) lock.unlock();
        }
    }

    public void setTransactionSuccessful() {
        if (!lock.isHeldByCurrentThread() || transactionDepth == 0) {
            throw new IllegalStateException("No transaction is in progress on this thread");
        }
        boolean[] level = transactionLevels.peek();
        if (level[0]) throw new IllegalStateException("setTransactionSuccessful called twice");
        level[0] = true;
    }

    public void endTransaction() {
        if (!lock.isHeldByCurrentThread() || transactionDepth == 0) {
            throw new IllegalStateException("No transaction is in progress on this thread");
        }
        try {
            if (!transactionLevels.pop()[0]) transactionFailed = true;
            transactionDepth--;
            if (transactionDepth == 0) {
                if (!closed) {
                    try (Statement s = connection.createStatement()) {
                        s.execute(transactionFailed ? "ROLLBACK" : "COMMIT");
                    } catch (SQLException e) {
                        if (!transactionFailed) {
                            try (Statement s = connection.createStatement()) {
                                s.execute("ROLLBACK");
                            } catch (SQLException ignored) {
                            }
                        }
                        throw new SQLiteException(e);
                    }
                }
                transactionFailed = false;
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean inTransaction() {
        return lock.isHeldByCurrentThread() && transactionDepth > 0;
    }

    public void execSQL(String sql) {
        execSQL(sql, null);
    }

    public void execSQL(String sql, Object[] bindArgs) {
        lock.lock();
        try {
            ensureOpen();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, bindArgs);
                statement.execute();
            }
        } catch (SQLException e) {
            throw new SQLiteException(e);
        } finally {
            lock.unlock();
        }
    }

    public Cursor rawQuery(String sql, Object[] selectionArgs) {
        Matcher export = EXPORT.matcher(sql);
        if (export.matches()) {
            exportTo(export.group(1));
            return new MaterializedCursor(new String[] {"sqlcipher_export"}, List.<Object[]>of(new Object[] {null}));
        }
        lock.lock();
        try {
            ensureOpen();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, selectionArgs);
                boolean hasResults = statement.execute();
                if (!hasResults) return new MaterializedCursor(new String[0], List.of());
                try (ResultSet results = statement.getResultSet()) {
                    return materialize(results);
                }
            }
        } catch (SQLException e) {
            throw new SQLiteException(e);
        } finally {
            lock.unlock();
        }
    }

    public long insert(String table, String nullColumnHack, ContentValues values) {
        try {
            return insertWithOnConflict(table, nullColumnHack, values, CONFLICT_NONE);
        } catch (SQLiteException e) {
            return -1L;
        }
    }

    public long insertOrThrow(String table, String nullColumnHack, ContentValues values) {
        return insertWithOnConflict(table, nullColumnHack, values, CONFLICT_NONE);
    }

    public long replace(String table, String nullColumnHack, ContentValues values) {
        try {
            return insertWithOnConflict(table, nullColumnHack, values, CONFLICT_REPLACE);
        } catch (SQLiteException e) {
            return -1L;
        }
    }

    public long insertWithOnConflict(String table, String nullColumnHack, ContentValues values, int conflictAlgorithm) {
        StringBuilder sql = new StringBuilder("INSERT").append(conflictClause(conflictAlgorithm)).append(" INTO ").append(table).append(" (");
        List<Object> args = new ArrayList<>();
        if (values == null || values.isEmpty()) {
            if (nullColumnHack == null) throw new IllegalArgumentException("Empty values need a null column hack");
            sql.append(nullColumnHack).append(") VALUES (NULL)");
        } else {
            StringBuilder placeholders = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, Object> entry : values.valueSet()) {
                if (!first) {
                    sql.append(", ");
                    placeholders.append(", ");
                }
                first = false;
                sql.append(entry.getKey());
                placeholders.append('?');
                args.add(entry.getValue());
            }
            sql.append(") VALUES (").append(placeholders).append(')');
        }
        lock.lock();
        try {
            ensureOpen();
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                bind(statement, args.toArray());
                int changed = statement.executeUpdate();
                if (changed == 0) return -1L;
            }
            try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("SELECT last_insert_rowid()")) {
                return r.next() ? r.getLong(1) : -1L;
            }
        } catch (SQLException e) {
            throw new SQLiteException(e);
        } finally {
            lock.unlock();
        }
    }

    public int update(String table, ContentValues values, String whereClause, Object[] whereArgs) {
        return updateWithOnConflict(table, values, whereClause, whereArgs, CONFLICT_NONE);
    }

    public int updateWithOnConflict(String table, ContentValues values, String whereClause, Object[] whereArgs, int conflictAlgorithm) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("Empty values");
        StringBuilder sql = new StringBuilder("UPDATE").append(conflictClause(conflictAlgorithm)).append(' ').append(table).append(" SET ");
        List<Object> args = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, Object> entry : values.valueSet()) {
            if (!first) sql.append(", ");
            first = false;
            sql.append(entry.getKey()).append(" = ?");
            args.add(entry.getValue());
        }
        if (whereClause != null && !whereClause.isEmpty()) sql.append(" WHERE ").append(whereClause);
        if (whereArgs != null) args.addAll(java.util.Arrays.asList(whereArgs));
        return executeUpdate(sql.toString(), args.toArray());
    }

    public int delete(String table, String whereClause, Object[] whereArgs) {
        String sql = "DELETE FROM " + table + (whereClause != null && !whereClause.isEmpty() ? " WHERE " + whereClause : "");
        return executeUpdate(sql, whereArgs);
    }

    /** Re-encrypts the database under a new raw 32-byte key. */
    public void rekey(byte[] newRawKey) {
        if (newRawKey == null || newRawKey.length != 32) throw new IllegalArgumentException("Raw SQLCipher keys are 32 bytes");
        char[] literal = rawKeyLiteral(newRawKey);
        try {
            execSQL("PRAGMA rekey = \"" + new String(literal) + "\"");
        } finally {
            java.util.Arrays.fill(literal, '\0');
        }
    }

    private int executeUpdate(String sql, Object[] args) {
        lock.lock();
        try {
            ensureOpen();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, args);
                return statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new SQLiteException(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * What SQLCipher's sqlcipher_export() does, which SQLite3 Multiple Ciphers doesn't have: copies
     * the schema and every row of the main database into an attached one, in one transaction, and
     * its user_version with them.
     */
    private void exportTo(String target) {
        beginTransaction();
        try {
            List<String[]> objects = new ArrayList<>();
            Cursor c = rawQuery(
                "SELECT type, name, sql FROM main.sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' ORDER BY rowid",
                null);
            try {
                while (c.moveToNext()) objects.add(new String[] {c.getString(0), c.getString(1), c.getString(2)});
            } finally {
                c.close();
            }
            for (String[] object : objects) {
                if (!"table".equals(object[0])) continue;
                Matcher m = CREATE_TABLE.matcher(object[2]);
                if (!m.find()) throw new SQLiteException("Unexpected table definition for " + object[1]);
                execSQL("CREATE TABLE " + target + "." + object[2].substring(m.end()));
                execSQL("INSERT INTO " + target + "." + quote(object[1]) + " SELECT * FROM main." + quote(object[1]));
            }
            for (String[] object : objects) {
                if (!"index".equals(object[0])) continue;
                Matcher m = CREATE_INDEX.matcher(object[2]);
                if (!m.find()) continue;
                String unique = m.group(1) != null ? "UNIQUE " : "";
                execSQL("CREATE " + unique + "INDEX " + target + "." + object[2].substring(m.end()));
            }
            int version = getVersion();
            execSQL("PRAGMA " + target + ".user_version = " + version);
            setTransactionSuccessful();
        } finally {
            endTransaction();
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Database is closed");
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String conflictClause(int conflictAlgorithm) {
        if (conflictAlgorithm < 0 || conflictAlgorithm >= CONFLICT_CLAUSES.length) {
            throw new IllegalArgumentException("Unknown conflict algorithm " + conflictAlgorithm);
        }
        return CONFLICT_CLAUSES[conflictAlgorithm];
    }

    private static void bind(PreparedStatement statement, Object[] args) throws SQLException {
        if (args == null) return;
        for (int i = 0; i < args.length; i++) {
            Object value = args[i];
            int index = i + 1;
            if (value == null) {
                statement.setNull(index, java.sql.Types.NULL);
            } else if (value instanceof byte[] bytes) {
                statement.setBytes(index, bytes);
            } else if (value instanceof Boolean b) {
                statement.setLong(index, b ? 1L : 0L);
            } else if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
                statement.setLong(index, ((Number) value).longValue());
            } else if (value instanceof Double || value instanceof Float) {
                statement.setDouble(index, ((Number) value).doubleValue());
            } else {
                statement.setString(index, value.toString());
            }
        }
    }

    private static Cursor materialize(ResultSet results) throws SQLException {
        ResultSetMetaData meta = results.getMetaData();
        int columns = meta.getColumnCount();
        String[] names = new String[columns];
        for (int i = 0; i < columns; i++) names[i] = meta.getColumnLabel(i + 1);
        List<Object[]> rows = new ArrayList<>();
        while (results.next()) {
            Object[] row = new Object[columns];
            for (int i = 0; i < columns; i++) {
                Object value = results.getObject(i + 1);
                if (value instanceof Integer n) value = n.longValue();
                else if (value instanceof Short n) value = n.longValue();
                else if (value instanceof Byte n) value = n.longValue();
                else if (value instanceof Float n) value = n.doubleValue();
                row[i] = value;
            }
            rows.add(row);
        }
        return new MaterializedCursor(names, rows);
    }

    private static char[] rawKeyLiteral(byte[] key) {
        char[] out = new char[3 + key.length * 2];
        out[0] = 'x';
        out[1] = '\'';
        for (int i = 0; i < key.length; i++) {
            out[2 + i * 2] = HEX[(key[i] >> 4) & 0xF];
            out[3 + i * 2] = HEX[key[i] & 0xF];
        }
        out[out.length - 1] = '\'';
        return out;
    }

    /** SQLCipher for Android's unchecked SQL exception. */
    public static final class SQLiteException extends RuntimeException {
        SQLiteException(String message) {
            super(message);
        }

        SQLiteException(SQLException cause) {
            super(cause.getMessage(), cause);
        }
    }

    private static final class MaterializedCursor implements Cursor {
        private final String[] columns;
        private final List<Object[]> rows;
        private int position = -1;
        private boolean closed = false;

        MaterializedCursor(String[] columns, List<Object[]> rows) {
            this.columns = columns;
            this.rows = rows;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public int getPosition() {
            return position;
        }

        @Override
        public boolean moveToFirst() {
            return moveToPosition(0);
        }

        @Override
        public boolean moveToNext() {
            return moveToPosition(position + 1);
        }

        @Override
        public boolean moveToPosition(int target) {
            if (target < 0) {
                position = -1;
                return false;
            }
            if (target >= rows.size()) {
                position = rows.size();
                return false;
            }
            position = target;
            return true;
        }

        @Override
        public boolean isAfterLast() {
            return rows.isEmpty() || position >= rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int columnIndex) {
            return columns[columnIndex];
        }

        @Override
        public String[] getColumnNames() {
            return columns.clone();
        }

        @Override
        public int getColumnIndex(String columnName) {
            int dot = columnName.lastIndexOf('.');
            String name = dot >= 0 ? columnName.substring(dot + 1) : columnName;
            for (int i = 0; i < columns.length; i++) {
                if (columns[i].equalsIgnoreCase(name)) return i;
            }
            return -1;
        }

        @Override
        public int getColumnIndexOrThrow(String columnName) {
            int index = getColumnIndex(columnName);
            if (index < 0) throw new IllegalArgumentException("column '" + columnName + "' does not exist");
            return index;
        }

        private Object value(int columnIndex) {
            if (closed) throw new IllegalStateException("Cursor is closed");
            if (position < 0 || position >= rows.size()) throw new IllegalStateException("Cursor is not on a row");
            if (columnIndex < 0 || columnIndex >= columns.length) throw new IllegalStateException("Column " + columnIndex + " out of range");
            return rows.get(position)[columnIndex];
        }

        @Override
        public int getType(int columnIndex) {
            Object v = value(columnIndex);
            if (v == null) return FIELD_TYPE_NULL;
            if (v instanceof Long) return FIELD_TYPE_INTEGER;
            if (v instanceof Double) return FIELD_TYPE_FLOAT;
            if (v instanceof byte[]) return FIELD_TYPE_BLOB;
            return FIELD_TYPE_STRING;
        }

        @Override
        public boolean isNull(int columnIndex) {
            return value(columnIndex) == null;
        }

        @Override
        public String getString(int columnIndex) {
            Object v = value(columnIndex);
            if (v == null) return null;
            if (v instanceof byte[]) throw new SQLiteException("unable to convert BLOB to string");
            if (v instanceof Double d) return formatReal(d);
            return v.toString();
        }

        @Override
        public byte[] getBlob(int columnIndex) {
            Object v = value(columnIndex);
            if (v == null) return null;
            if (v instanceof byte[] bytes) return bytes.clone();
            return getString(columnIndex).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public short getShort(int columnIndex) {
            return (short) getLong(columnIndex);
        }

        @Override
        public int getInt(int columnIndex) {
            return (int) getLong(columnIndex);
        }

        @Override
        public long getLong(int columnIndex) {
            Object v = value(columnIndex);
            if (v == null) return 0L;
            if (v instanceof Long n) return n;
            if (v instanceof Double d) return d.longValue();
            if (v instanceof byte[]) throw new SQLiteException("unable to convert BLOB to long");
            return parseLeadingNumber(v.toString()).longValue();
        }

        @Override
        public float getFloat(int columnIndex) {
            return (float) getDouble(columnIndex);
        }

        @Override
        public double getDouble(int columnIndex) {
            Object v = value(columnIndex);
            if (v == null) return 0.0;
            if (v instanceof Number n) return n.doubleValue();
            if (v instanceof byte[]) throw new SQLiteException("unable to convert BLOB to double");
            return parseLeadingNumber(v.toString()).doubleValue();
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            closed = true;
        }

        /** SQLite reads the longest numeric prefix of text and treats the rest as absent. */
        private static Number parseLeadingNumber(String text) {
            String s = text.trim();
            Matcher m = Pattern.compile("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?").matcher(s);
            if (!m.find()) return 0L;
            String number = m.group();
            if (m.group(2) == null && m.group(3) == null && !number.startsWith(".")) {
                try {
                    return Long.parseLong(number);
                } catch (NumberFormatException overflow) {
                    return Double.parseDouble(number);
                }
            }
            return Double.parseDouble(number);
        }

        private static String formatReal(double d) {
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                return String.format(Locale.ROOT, "%.1f", d);
            }
            return Double.toString(d);
        }
    }
}
