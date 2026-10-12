package io.smarthome.logic.tinyrpc;

import com.alibaba.fastjson.JSONObject;
import io.smarthome.logic.LogicModel;
import io.smarthome.logic.TypedValue;
import io.smarthome.tinyrpc.netty.TinyRpcServer;
import io.smarthome.tinyrpc.telemetry.TransportObservation;
import org.junit.Test;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class TinyRpcGraphCliTest {
    private static final String DESCRIPTOR = "{\"schemaVersion\":\"1.0.1\",\"serviceId\":\"switch.service\",\"version\":\"1.0.0\","
            + "\"endpoint\":{\"catalogId\":\"local.switch\"},\"transport\":{\"kind\":\"tiny-rpc-netty\",\"protocolVersion\":\"tiny-rpc/1\"},"
            + "\"operations\":[{\"name\":\"readEnabled\",\"parameters\":[],\"returns\":\"boolean\"}],"
            + "\"metadata\":{\"capabilityState\":\"experimental\"}}";

    @Test public void graphCliInvokesProviderAndEmitsPayloadFreeSameSessionTrace() throws Exception {
        LogicModel.ServiceDescriptor descriptor = ServiceDescriptorJson.read(DESCRIPTOR);
        TinyRpcServer.Builder builder = TinyRpcServer.builder();
        TinyRpcTypedProvider.register(builder, "switch.service", "readEnabled", arguments -> TypedValue.ofBoolean(true));
        List<TransportObservation> frames = new CopyOnWriteArrayList<>();
        try (TinyRpcServer server = builder.build().bind("127.0.0.1", 0)) {
            JSONObject result = TinyRpcLogicCli.invokeGraph("127.0.0.1", server.port(), "switch.service", "readEnabled", "[]", descriptor, frames);
            assertEquals("success", result.getString("status"));
            assertEquals(Boolean.TRUE, result.getJSONObject("value").get("value"));
            JSONObject trace = TinyRpcLogicCli.trace(frames);
            assertEquals("1.0.2", trace.getString("schemaVersion"));
            assertEquals("instrumented-rpc", trace.getString("sourceKind"));
            assertEquals(2, trace.getJSONArray("observations").size());
            for (Object item : trace.getJSONArray("observations")) {
                JSONObject frame = (JSONObject) item;
                assertEquals(9, frame.size());
                assertFalse(frame.containsKey("payload")); assertFalse(frame.containsKey("endpoint"));
                assertEquals("1.0.2", frame.getString("schemaVersion"));
                assertEquals(trace.get("traceId"), frame.get("traceId"));
            }
            List<TransportObservation> other = new CopyOnWriteArrayList<>();
            TinyRpcLogicCli.invokeGraph("127.0.0.1", server.port(), "switch.service", "readEnabled", "[]", descriptor, other);
            assertNotEquals(frames.get(0).getSessionId(), other.get(0).getSessionId());
        }
    }

    @Test public void graphSignatureRejectionHasNoNetworkSideEffect() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TinyRpcServer.Builder builder = TinyRpcServer.builder();
        TinyRpcTypedProvider.register(builder, "switch.service", "readEnabled", arguments -> { calls.incrementAndGet(); return TypedValue.ofBoolean(true); });
        List<TransportObservation> frames = new CopyOnWriteArrayList<>();
        try (TinyRpcServer server = builder.build().bind("127.0.0.1", 0)) {
            try {
                TinyRpcLogicCli.invokeGraph("127.0.0.1", server.port(), "switch.service", "readEnabled", "[{\"type\":\"boolean\",\"value\":true}]", ServiceDescriptorJson.read(DESCRIPTOR), frames);
                fail("Expected preflight failure");
            } catch (io.smarthome.logic.LogicFailure expected) { }
            assertEquals(0, calls.get()); assertTrue(frames.isEmpty());
        }
    }

    @Test public void descriptorRejectsUnsupportedMajorUnknownFieldsAndCoercion() {
        for (String invalid : new String[]{DESCRIPTOR.replace("1.0.1", "2.0.0"),
                DESCRIPTOR.replace("\"version\":\"1.0.0\"", "\"version\":100"),
                DESCRIPTOR.replace("\"catalogId\":\"local.switch\"", "\"catalogId\":\"local.switch\",\"host\":\"localhost\""),
                DESCRIPTOR.replace("\"returns\":\"boolean\"", "\"returns\":\"boolean\",\"returnFields\":[]")}) {
            try { ServiceDescriptorJson.read(invalid); fail("Expected descriptor rejection"); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
