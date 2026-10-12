package io.smarthome.logic.tinyrpc;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.parser.ParserConfig;
import io.smarthome.logic.LogicModel;
import io.smarthome.logic.TypedValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Strict Hub descriptor boundary; addresses remain in the separate local endpoint catalog. */
final class ServiceDescriptorJson {
    private ServiceDescriptorJson() { }

    static LogicModel.ServiceDescriptor read(String json) {
        if (json == null || json.length() > 1048576) throw new IllegalArgumentException("Descriptor exceeds size limit");
        ParserConfig config = new ParserConfig(); config.setAutoTypeSupport(false); config.setSafeMode(true);
        JSONObject root = object(JSON.parseObject(json, Object.class, config, Feature.DisableSpecialKeyDetect));
        keys(root, "schemaVersion", "serviceId", "version", "endpoint", "transport", "operations", "metadata");
        if (!string(root, "schemaVersion").matches("1\\.[0-9]+\\.[0-9]+")) throw new IllegalArgumentException("Unsupported descriptor major");
        if (!string(root, "version").matches("[0-9]+\\.[0-9]+\\.[0-9]+")) throw new IllegalArgumentException("Invalid descriptor version");
        JSONObject endpoint = object(root.get("endpoint")); keys(endpoint, "catalogId");
        JSONObject transport = object(root.get("transport")); keys(transport, "kind", "protocolVersion");
        JSONObject metadata = object(root.get("metadata")); keys(metadata, "capabilityState");
        if (!Arrays.asList("historical", "experimental", "verified-component", "verified-integration", "future")
                .contains(string(metadata, "capabilityState"))) throw new IllegalArgumentException("Invalid capability state");
        List<LogicModel.OperationDescriptor> operations = new ArrayList<>();
        for (Object item : array(root.get("operations"))) {
            JSONObject operation = object(item); allowed(operation, "name", "parameters", "returns", "returnFields");
            List<LogicModel.TypeSignature> parameters = new ArrayList<>();
            for (Object argument : array(operation.get("parameters"))) {
                JSONObject parameter = object(argument); allowed(parameter, "name", "type", "fields");
                String name = string(parameter, "name");
                if (!name.matches("[a-zA-Z][a-zA-Z0-9._:-]{2,127}")) throw new IllegalArgumentException("Invalid parameter name");
                parameters.add(signature(parameter, "type", "fields"));
            }
            operations.add(new LogicModel.OperationDescriptor(string(operation, "name"), parameters,
                    signature(operation, "returns", "returnFields")));
        }
        if (operations.isEmpty()) throw new IllegalArgumentException("Descriptor needs operations");
        return new LogicModel.ServiceDescriptor(string(root, "serviceId"), string(root, "version"), string(endpoint, "catalogId"),
                string(transport, "kind"), string(transport, "protocolVersion"), operations);
    }

    private static LogicModel.TypeSignature signature(JSONObject value, String typeKey, String fieldsKey) {
        TypedValue.Type type = type(string(value, typeKey));
        if (type != TypedValue.Type.RECORD && value.containsKey(fieldsKey)) throw new IllegalArgumentException("Scalar signature declares fields");
        if (type == TypedValue.Type.RECORD && !value.containsKey(fieldsKey)) throw new IllegalArgumentException("Record signature requires fields");
        return new LogicModel.TypeSignature(type, fields(value.get(fieldsKey), 1));
    }
    private static List<LogicModel.FieldDescriptor> fields(Object raw, int depth) {
        if (raw == null) return Collections.emptyList();
        if (depth > 4) throw new IllegalArgumentException("Record signature exceeds four levels");
        JSONArray items = array(raw);
        if (items.isEmpty() || items.size() > 32) throw new IllegalArgumentException("Invalid record field count");
        List<LogicModel.FieldDescriptor> fields = new ArrayList<>();
        for (Object item : items) {
            JSONObject field = object(item); allowed(field, "name", "type", "fields");
            TypedValue.Type type = type(string(field, "type"));
            if (type != TypedValue.Type.RECORD && field.containsKey("fields")) throw new IllegalArgumentException("Scalar field declares fields");
            if (type == TypedValue.Type.RECORD && !field.containsKey("fields")) throw new IllegalArgumentException("Record field requires fields");
            fields.add(new LogicModel.FieldDescriptor(string(field, "name"), type, fields(field.get("fields"), depth + 1)));
        }
        return fields;
    }
    private static TypedValue.Type type(String value) {
        if (!Arrays.asList("boolean", "integer", "number", "string", "null", "record").contains(value))
            throw new IllegalArgumentException("Unknown signature type");
        return TypedValue.Type.valueOf(value.toUpperCase(Locale.ROOT));
    }
    private static String string(JSONObject value, String key) {
        Object raw = value.get(key);
        if (!(raw instanceof String)) throw new IllegalArgumentException("Expected string: " + key);
        return (String) raw;
    }
    private static JSONObject object(Object raw) {
        if (!(raw instanceof JSONObject)) throw new IllegalArgumentException("Expected descriptor object");
        return (JSONObject) raw;
    }
    private static JSONArray array(Object raw) {
        if (!(raw instanceof JSONArray)) throw new IllegalArgumentException("Expected descriptor array");
        return (JSONArray) raw;
    }
    private static void allowed(JSONObject object, String... allowed) {
        if (!Arrays.asList(allowed).containsAll(object.keySet())) throw new IllegalArgumentException("Unknown descriptor fields");
    }
    private static void keys(JSONObject object, String... keys) {
        allowed(object, keys);
        if (object.size() != keys.length) throw new IllegalArgumentException("Missing descriptor fields");
    }
}
