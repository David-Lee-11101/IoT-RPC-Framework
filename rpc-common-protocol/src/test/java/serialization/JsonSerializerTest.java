package serialization;

import com.alibaba.fastjson.JSONException;
import org.junit.Test;
import protocol.ProtocolResponse;

import java.nio.charset.StandardCharsets;

public class JsonSerializerTest {

    @Test(expected = JSONException.class)
    public void rejectsAutoTypeMetadataInsideObjectResponseField() {
        byte[] payload = ("{\"data\":{\"@type\":\"java.lang.Exception\","
                + "\"message\":\"must not instantiate\"},\"msg\":\"failure\"}")
                .getBytes(StandardCharsets.UTF_8);

        new JsonSerializer().deserialize(payload, ProtocolResponse.class);
    }
}
