package io.smarthome.logic;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.parser.ParserConfig;
import com.alibaba.fastjson.serializer.SerializerFeature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TypedValueJson {
    private TypedValueJson() { }

    public static String write(TypedValue value) { return JSON.toJSONString(object(value), SerializerFeature.WriteMapNullValue); }
    public static String writeList(List<TypedValue> values) {
        JSONArray array = new JSONArray();
        for (TypedValue value : values) array.add(object(value));
        return JSON.toJSONString(array, SerializerFeature.WriteMapNullValue);
    }
    public static TypedValue read(String json) { return value(parse(json)); }
    public static List<TypedValue> readList(String json) {
        Object parsed = parse(json);
        if (!(parsed instanceof JSONArray)) throw new IllegalArgumentException("Typed arguments must be a JSON array");
        List<TypedValue> values = new ArrayList<>();
        for (Object element : (JSONArray) parsed) values.add(value(element));
        return Collections.unmodifiableList(values);
    }

    private static Object parse(String json) {
        if (json == null || json.contains("\"@type\"") || json.contains("\"$ref\""))
            throw new IllegalArgumentException("Special JSON keys are forbidden");
        ParserConfig config = new ParserConfig(); config.setAutoTypeSupport(false); config.setSafeMode(true);
        return JSON.parseObject(json, Object.class, config, Feature.DisableSpecialKeyDetect);
    }

    private static JSONObject object(TypedValue value) {
        if (value == null) throw new IllegalArgumentException("Typed value is required");
        JSONObject object = new JSONObject(true); object.put("type", value.getType().name().toLowerCase());
        if (value.getType() == TypedValue.Type.RECORD) {
            JSONObject record = new JSONObject(true);
            for (Map.Entry<String, TypedValue> field : value.asRecord().entrySet()) record.put(field.getKey(), object(field.getValue()));
            object.put("value", record);
        } else object.put("value", value.getValue());
        return object;
    }

    private static TypedValue value(Object raw) {
        if (!(raw instanceof JSONObject)) throw new IllegalArgumentException("Typed value must be an object");
        JSONObject object = (JSONObject) raw;
        if (object.size() != 2 || !object.containsKey("type") || !object.containsKey("value"))
            throw new IllegalArgumentException("Typed value must contain exactly type and value: " + object.keySet());
        if (!(object.get("type") instanceof String)) throw new IllegalArgumentException("Typed value type must be a string");
        Object payload = object.get("value");
        switch ((String) object.get("type")) {
            case "boolean": if (payload instanceof Boolean) return TypedValue.ofBoolean((Boolean) payload); break;
            case "integer": if (payload instanceof Byte || payload instanceof Short || payload instanceof Integer || payload instanceof Long) return TypedValue.ofInteger(((Number) payload).longValue()); break;
            case "number": if (payload instanceof Number) return TypedValue.ofNumber(((Number) payload).doubleValue()); break;
            case "string": if (payload instanceof String) return TypedValue.ofString((String) payload); break;
            case "null": if (payload == null) return TypedValue.nullValue(); break;
            case "record":
                if (payload instanceof JSONObject) {
                    Map<String, TypedValue> fields = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> field : ((JSONObject) payload).entrySet()) fields.put(field.getKey(), value(field.getValue()));
                    return TypedValue.record(fields);
                }
                break;
            default: throw new IllegalArgumentException("Unsupported typed value type");
        }
        throw new IllegalArgumentException("Declared type does not match JSON value");
    }
}
