package io.smarthome.logic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TypedValue {
    public enum Type { BOOLEAN, INTEGER, NUMBER, STRING, NULL, RECORD }
    private final Type type;
    private final Object value;

    private TypedValue(Type type, Object value) { this.type = type; this.value = value; validate(1); }
    public static TypedValue ofBoolean(boolean value) { return new TypedValue(Type.BOOLEAN, value); }
    public static TypedValue ofInteger(long value) { return new TypedValue(Type.INTEGER, value); }
    public static TypedValue ofNumber(double value) { return new TypedValue(Type.NUMBER, value); }
    public static TypedValue ofString(String value) {
        if (value == null) throw new IllegalArgumentException("String value is required");
        return new TypedValue(Type.STRING, value);
    }
    public static TypedValue nullValue() { return new TypedValue(Type.NULL, null); }
    public static TypedValue record(Map<String, TypedValue> fields) {
        if (fields == null) throw new IllegalArgumentException("Record fields are required");
        return new TypedValue(Type.RECORD, Collections.unmodifiableMap(new LinkedHashMap<>(fields)));
    }
    public Type getType() { return type; }
    public Object getValue() { return value; }
    @SuppressWarnings("unchecked") public Map<String, TypedValue> asRecord() { return Collections.unmodifiableMap((Map<String, TypedValue>) value); }
    public double asNumber() {
        if (type == Type.INTEGER) return ((Long) value).doubleValue();
        if (type == Type.NUMBER) return (Double) value;
        throw new IllegalStateException("Value is not numeric");
    }
    public boolean asBoolean() { if (type != Type.BOOLEAN) throw new IllegalStateException("Value is not boolean"); return (Boolean) value; }

    private void validate(int depth) {
        if (type == null) throw new IllegalArgumentException("type is required");
        switch (type) {
            case BOOLEAN: if (!(value instanceof Boolean)) mismatch(); break;
            case INTEGER: if (!(value instanceof Long)) mismatch(); break;
            case NUMBER:
                if (!(value instanceof Double) || !Double.isFinite((Double) value)) mismatch(); break;
            case STRING: if (!(value instanceof String)) mismatch(); break;
            case NULL: if (value != null) mismatch(); break;
            case RECORD:
                if (!(value instanceof Map)) mismatch();
                if (depth > 4) throw new IllegalArgumentException("Record nesting exceeds four levels");
                Map<?, ?> fields = (Map<?, ?>) value;
                if (fields.size() > 32) throw new IllegalArgumentException("Record contains more than 32 fields");
                for (Map.Entry<?, ?> entry : fields.entrySet()) {
                    if (!(entry.getKey() instanceof String) || !((String) entry.getKey()).matches("[a-zA-Z][a-zA-Z0-9._-]{0,63}"))
                        throw new IllegalArgumentException("Invalid record field name");
                    if (!(entry.getValue() instanceof TypedValue)) throw new IllegalArgumentException("Record members must be typed values");
                    ((TypedValue) entry.getValue()).validate(depth + 1);
                }
                break;
            default: throw new IllegalArgumentException("Unsupported type");
        }
    }
    private static void mismatch() { throw new IllegalArgumentException("Declared type does not match value"); }
}
