package io.smarthome.logic.tinyrpc;

import io.smarthome.logic.LocalServiceCatalog;
import io.smarthome.logic.LogicEngine;
import io.smarthome.logic.LogicExecutionResult;
import io.smarthome.logic.LogicModel;
import io.smarthome.logic.TypedValue;
import io.smarthome.tinyrpc.core.RpcLimits;
import io.smarthome.tinyrpc.netty.TinyRpcServer;
import io.smarthome.tinyrpc.telemetry.TransportObservation;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TinyRpcLogicIntegrationTest {
    @Test public void executesTypedDagAcrossRealTinyRpcLoopback() throws Exception {
        RpcLimits limits = new RpcLimits(64 * 1024, 16, 16, 16);
        TinyRpcServer.Builder builder = TinyRpcServer.builder().limits(limits);
        TinyRpcTypedProvider.register(builder, "sensor.service", "read.state", arguments -> sensor(23.5, "comfort"));
        List<TransportObservation> observations = new CopyOnWriteArrayList<>();
        try (TinyRpcServer server = builder.build().bind("127.0.0.1", 0);
             TinyRpcTransportAdapter adapter = new TinyRpcTransportAdapter(
                     new LocalEndpointCatalog().register("local.catalog", "127.0.0.1", server.port()), limits, observations::add)) {
            LogicEngine engine = new LogicEngine(new LocalServiceCatalog().register(service()), Collections.singletonList(adapter));
            LogicExecutionResult result = engine.execute(graph());
            assertTrue(result.getOutputs().get("comfort.result").asBoolean());
            assertEquals(2, observations.size());
            assertEquals("client-to-server", observations.get(0).getDirection());
            assertEquals("server-to-client", observations.get(1).getDirection());
            assertEquals(observations.get(0).getTraceId(), observations.get(1).getTraceId());
            assertEquals(observations.get(0).getSessionId(), observations.get(1).getSessionId());
            assertTrue(observations.get(1).getMonotonicOffsetNanos() >= observations.get(0).getMonotonicOffsetNanos());
        }
    }

    @Test public void rejectsLegacyDescriptorWithoutNetworkFallback() {
        LogicModel.ServiceDescriptor legacy = new LogicModel.ServiceDescriptor("sensor.service", "v1.0", "local.catalog",
                "legacy-typed-netty", "v1.0", Collections.singletonList(new LogicModel.OperationDescriptor("read.state", Collections.emptyList(), sensorSignature())));
        TinyRpcTransportAdapter adapter = new TinyRpcTransportAdapter(new LocalEndpointCatalog(), RpcLimits.defaults(), null);
        try {
            adapter.invoke(legacy, "read.state", Collections.emptyList(), System.currentTimeMillis() + 1000, "request-001").join();
            throw new AssertionError("Expected transport rejection");
        } catch (java.util.concurrent.CompletionException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        } finally { adapter.close(); }
    }

    private static LogicModel.ServiceDescriptor service() {
        return new LogicModel.ServiceDescriptor("sensor.service", "v1.0", "local.catalog", "tiny-rpc-netty", "v1.0",
                Collections.singletonList(new LogicModel.OperationDescriptor("read.state", Collections.emptyList(), sensorSignature())));
    }
    private static LogicModel.TypeSignature sensorSignature() {
        return new LogicModel.TypeSignature(TypedValue.Type.RECORD, Arrays.asList(
                new LogicModel.FieldDescriptor("temperature", TypedValue.Type.NUMBER), new LogicModel.FieldDescriptor("mode", TypedValue.Type.STRING)));
    }
    private static LogicModel.Definition graph() {
        LogicModel.Node call = new LogicModel.Node("sensor.read", "service-call", Collections.emptyList(), sensorSignature(), "sensor.service", "read.state", null, null);
        LogicModel.Node select = new LogicModel.Node("temperature.pick", "select-field", Collections.singletonList(LogicModel.Binding.node("sensor.read")),
                new LogicModel.TypeSignature(TypedValue.Type.NUMBER), null, null, null, "temperature");
        LogicModel.Node compare = new LogicModel.Node("comfort.check", "compare", Arrays.asList(LogicModel.Binding.node("temperature.pick"), LogicModel.Binding.constant(TypedValue.ofNumber(22))),
                new LogicModel.TypeSignature(TypedValue.Type.BOOLEAN), null, null, "gt", null);
        return new LogicModel.Definition("graph.integration", Arrays.asList(call, select, compare),
                Collections.singletonList(new LogicModel.Output("comfort.result", "comfort.check")), 2000, 1000);
    }
    private static TypedValue sensor(double value, String mode) {
        Map<String, TypedValue> fields = new LinkedHashMap<>(); fields.put("temperature", TypedValue.ofNumber(value)); fields.put("mode", TypedValue.ofString(mode));
        return TypedValue.record(fields);
    }
}
