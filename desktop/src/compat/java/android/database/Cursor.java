package android.database;

import java.io.Closeable;

/**
 * The part of Android's Cursor the shared storage code uses. Desktop builds only. Columns are read
 * the way Android reads them from SQLite: values convert between integer, real and text the way
 * SQLite itself converts them.
 */
public interface Cursor extends Closeable {

    int FIELD_TYPE_NULL = 0;
    int FIELD_TYPE_INTEGER = 1;
    int FIELD_TYPE_FLOAT = 2;
    int FIELD_TYPE_STRING = 3;
    int FIELD_TYPE_BLOB = 4;

    int getCount();

    int getPosition();

    boolean moveToFirst();

    boolean moveToNext();

    boolean moveToPosition(int position);

    boolean isAfterLast();

    int getColumnCount();

    String getColumnName(int columnIndex);

    String[] getColumnNames();

    int getColumnIndex(String columnName);

    int getColumnIndexOrThrow(String columnName) throws IllegalArgumentException;

    int getType(int columnIndex);

    boolean isNull(int columnIndex);

    String getString(int columnIndex);

    byte[] getBlob(int columnIndex);

    short getShort(int columnIndex);

    int getInt(int columnIndex);

    long getLong(int columnIndex);

    float getFloat(int columnIndex);

    double getDouble(int columnIndex);

    boolean isClosed();

    @Override
    void close();
}
