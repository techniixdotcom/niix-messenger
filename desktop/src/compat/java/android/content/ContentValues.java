package android.content;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The part of Android's ContentValues the shared storage code uses: an ordered map of column names
 * to values. Desktop builds only; the Android build uses the real class.
 */
public final class ContentValues {

    private final LinkedHashMap<String, Object> values = new LinkedHashMap<>();

    public ContentValues() {
    }

    public void put(String key, String value) {
        values.put(key, value);
    }

    public void put(String key, Byte value) {
        values.put(key, value);
    }

    public void put(String key, Short value) {
        values.put(key, value);
    }

    public void put(String key, Integer value) {
        values.put(key, value);
    }

    public void put(String key, Long value) {
        values.put(key, value);
    }

    public void put(String key, Float value) {
        values.put(key, value);
    }

    public void put(String key, Double value) {
        values.put(key, value);
    }

    public void put(String key, Boolean value) {
        values.put(key, value);
    }

    public void put(String key, byte[] value) {
        values.put(key, value);
    }

    public void putNull(String key) {
        values.put(key, null);
    }

    public Object get(String key) {
        return values.get(key);
    }

    public boolean containsKey(String key) {
        return values.containsKey(key);
    }

    public void remove(String key) {
        values.remove(key);
    }

    public void clear() {
        values.clear();
    }

    public int size() {
        return values.size();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public Set<String> keySet() {
        return Collections.unmodifiableSet(values.keySet());
    }

    public Set<Map.Entry<String, Object>> valueSet() {
        return Collections.unmodifiableSet(values.entrySet());
    }
}
