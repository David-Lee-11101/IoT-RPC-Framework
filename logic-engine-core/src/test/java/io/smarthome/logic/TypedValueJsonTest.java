package io.smarthome.logic;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class TypedValueJsonTest {
    @Test public void roundTripsBoundedRecordsAndLists() {
        Map<String, TypedValue> fields = new LinkedHashMap<>(); fields.put("temperature", TypedValue.ofNumber(21.5)); fields.put("active", TypedValue.ofBoolean(true));
        TypedValue record = TypedValue.record(fields);
        assertEquals(21.5, TypedValueJson.read(TypedValueJson.write(record)).asRecord().get("temperature").asNumber(), 0D);
        assertEquals(2, TypedValueJson.readList(TypedValueJson.writeList(Arrays.asList(record, TypedValue.nullValue()))).size());
    }
    @Test public void rejectsUnknownSpecialAndMismatchedMembers() {
        expectInvalid("{\"type\":\"string\",\"value\":\"ok\",\"extra\":true}");
        expectInvalid("{\"type\":\"integer\",\"value\":1.5}");
        expectInvalid("{\"@type\":\"java.lang.Runtime\",\"type\":\"null\",\"value\":null}");
    }
    private static void expectInvalid(String json) {
        try { TypedValueJson.read(json); throw new AssertionError("Expected invalid typed JSON"); }
        catch (IllegalArgumentException expected) { }
    }
}
