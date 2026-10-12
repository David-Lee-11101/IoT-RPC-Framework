package io.smarthome.logic.tinyrpc;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;
import io.smarthome.logic.LocalServiceCatalog;
import io.smarthome.logic.LogicEngine;
import io.smarthome.logic.LogicExecutionResult;
import io.smarthome.logic.LogicModel;
import io.smarthome.tinyrpc.telemetry.TransportObservation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import io.smarthome.logic.TypedValue;
import io.smarthome.logic.TypedValueJson;
import io.smarthome.tinyrpc.core.RpcLimits;
import io.smarthome.tinyrpc.core.RpcRequest;
import io.smarthome.tinyrpc.core.RpcResult;
import io.smarthome.tinyrpc.netty.TinyRpcClient;
import io.smarthome.tinyrpc.netty.TinyRpcServer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class TinyRpcLogicCli {
    private TinyRpcLogicCli() { }
    public static void main(String[] args) throws Exception {
        if (args.length == 10 && "invoke-graph".equals(args[0]) && "--descriptor-file".equals(args[6]) && "--trace-file".equals(args[8])) {
            runGraph(args[1], Integer.parseInt(args[2]), args[3], args[4], args[5], Paths.get(args[7]), Paths.get(args[9])); return;
        }
        if (args.length == 5 && "provider".equals(args[0])) {
            runProvider(Integer.parseInt(args[1]), args[2], args[3], TypedValueJson.read(args[4])); return;
        }
        if (args.length == 6 && "invoke".equals(args[0])) {
            runInvoke(args[1], Integer.parseInt(args[2]), args[3], args[4], args[5]); return;
        }
        throw new IllegalArgumentException("Usage: invoke-graph <host> <port> <serviceId> <operation> <typedArgumentsJson> --descriptor-file <path> --trace-file <new-path> | provider <port|0> <serviceId> <operation> <typedValueJson> | invoke <host> <port> <serviceId> <operation> <typedArgumentsJson>");
    }

    private static void runProvider(int port, String serviceId, String operation, TypedValue result) throws Exception {
        TinyRpcServer.Builder builder = TinyRpcServer.builder();
        TinyRpcTypedProvider.register(builder, serviceId, operation, arguments -> result);
        TinyRpcServer server = builder.build().bind("127.0.0.1", port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "tiny-rpc-provider-shutdown"));
        JSONObject ready = new JSONObject(true); ready.put("event", "provider-ready"); ready.put("catalogId", "local.catalog");
        ready.put("host", "127.0.0.1"); ready.put("port", server.port()); ready.put("serviceId", serviceId); ready.put("operation", operation);
        System.out.println(ready.toJSONString()); System.out.flush();
        new CountDownLatch(1).await();
    }

    private static void runInvoke(String host, int port, String serviceId, String operation, String argumentsJson) throws Exception {
        String normalized = TypedValueJson.writeList(TypedValueJson.readList(argumentsJson));
        try (TinyRpcClient client = TinyRpcClient.connect(host, port, RpcLimits.defaults(), null)) {
            RpcResult result = client.invoke(new RpcRequest("trace.cli.001", "session.cli.001", "request.cli.001",
                    serviceId, operation, normalized, System.currentTimeMillis() + 3000)).get(4, TimeUnit.SECONDS);
            JSONObject output = new JSONObject(true); output.put("event", "invocation-result"); output.put("requestId", result.getRequestId());
            output.put("status", result.getStatus());
            if (result.isSuccess()) output.put("value", JSONValue.parse(result.getValueJson()));
            else { output.put("errorCode", result.getErrorCode()); output.put("errorMessage", result.getErrorMessage()); }
            System.out.println(output.toJSONString());
        }
    }

    static JSONObject invokeGraph(String host, int port, String serviceId, String operation, String argumentsJson,
                                  LogicModel.ServiceDescriptor descriptor, List<TransportObservation> observations) throws Exception {
        if (!serviceId.equals(descriptor.getServiceId())) throw new IllegalArgumentException("Descriptor service differs from invocation");
        LogicModel.OperationDescriptor signature = descriptor.operation(operation);
        if (signature == null) throw new IllegalArgumentException("Operation is not registered");
        List<LogicModel.Binding> bindings = new ArrayList<>();
        for (TypedValue argument : TypedValueJson.readList(argumentsJson)) bindings.add(LogicModel.Binding.constant(argument));
        LogicModel.Node call = new LogicModel.Node("invoke.service", "service-call", bindings, signature.getReturns(), serviceId, operation, null, null);
        LogicModel.Definition definition = new LogicModel.Definition("graph.cli.invoke", Collections.singletonList(call),
                Collections.singletonList(new LogicModel.Output("invoke.result", "invoke.service")), 4000, 3000);
        try (TinyRpcTransportAdapter adapter = new TinyRpcTransportAdapter(new LocalEndpointCatalog()
                .register(descriptor.getCatalogId(), host, port), RpcLimits.defaults(), observations::add)) {
            LogicEngine engine = new LogicEngine(new LocalServiceCatalog().register(descriptor), Collections.singletonList(adapter));
            LogicExecutionResult result = engine.execute(definition);
            JSONObject output = new JSONObject(true); output.put("event", "invocation-result"); output.put("status", "success");
            output.put("graphId", result.getGraphId()); output.put("requestId", observations.get(0).getRequestId());
            output.put("logicDefinition", graphJson(definition));
            output.put("value", JSONValue.parse(TypedValueJson.write(result.getOutputs().get("invoke.result"))));
            return output;
        }
    }

    private static JSONObject graphJson(LogicModel.Definition definition) {
        JSONObject graph = new JSONObject(true); graph.put("schemaVersion", definition.getSchemaVersion());
        graph.put("graphId", definition.getGraphId()); JSONArray nodes = new JSONArray();
        for (LogicModel.Node node : definition.getNodes()) {
            JSONObject value = new JSONObject(true); value.put("nodeId", node.getNodeId()); value.put("kind", node.getKind());
            value.put("serviceId", node.getServiceId()); value.put("operation", node.getOperation());
            value.put("resultType", node.getResult().getType().name().toLowerCase(java.util.Locale.ROOT));
            if (node.getResult().getType() == TypedValue.Type.RECORD) value.put("resultFields", fieldJson(node.getResult().getFields()));
            JSONArray inputs = new JSONArray();
            for (LogicModel.Binding binding : node.getInputs()) {
                JSONObject input = new JSONObject(true); input.put("bindingKind", "constant");
                input.put("constant", JSONValue.parse(TypedValueJson.write(binding.getConstant()))); inputs.add(input);
            }
            value.put("inputs", inputs); nodes.add(value);
        }
        graph.put("nodes", nodes); JSONArray outputs = new JSONArray();
        for (LogicModel.Output output : definition.getOutputs()) {
            JSONObject value = new JSONObject(true); value.put("name", output.getName()); value.put("nodeId", output.getNodeId()); outputs.add(value);
        }
        graph.put("outputs", outputs); JSONObject timeout = new JSONObject(true);
        timeout.put("graphTimeoutMs", definition.getGraphTimeoutMs()); timeout.put("nodeTimeoutMs", definition.getNodeTimeoutMs());
        graph.put("timeoutPolicy", timeout); return graph;
    }

    private static JSONArray fieldJson(List<LogicModel.FieldDescriptor> fields) {
        JSONArray values = new JSONArray();
        for (LogicModel.FieldDescriptor field : fields) {
            JSONObject value = new JSONObject(true); value.put("name", field.getName());
            value.put("type", field.getType().name().toLowerCase(java.util.Locale.ROOT));
            if (field.getType() == TypedValue.Type.RECORD) value.put("fields", fieldJson(field.getFields()));
            values.add(value);
        }
        return values;
    }

    private static void runGraph(String host, int port, String serviceId, String operation, String argumentsJson,
                                 Path descriptorFile, Path traceFile) throws Exception {
        if (Files.size(descriptorFile) > 1048576) throw new IllegalArgumentException("Descriptor exceeds size limit");
        LogicModel.ServiceDescriptor descriptor = ServiceDescriptorJson.read(new String(Files.readAllBytes(descriptorFile), StandardCharsets.UTF_8));
        List<TransportObservation> observations = new CopyOnWriteArrayList<>();
        JSONObject output = invokeGraph(host, port, serviceId, operation, argumentsJson, descriptor, observations);
        Files.write(traceFile, trace(observations).toJSONString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        System.out.println(output.toJSONString());
    }

    static JSONObject trace(List<TransportObservation> observations) {
        if (observations.size() != 2) throw new IllegalStateException("Expected one complete RPC exchange");
        TransportObservation first = observations.get(0), second = observations.get(1);
        if (!first.getTraceId().equals(second.getTraceId()) || !first.getSessionId().equals(second.getSessionId())
                || !first.getRequestId().equals(second.getRequestId()) || !"client-to-server".equals(first.getDirection())
                || !"server-to-client".equals(second.getDirection()) || first.getMonotonicOffsetNanos() < 0
                || second.getMonotonicOffsetNanos() < first.getMonotonicOffsetNanos())
            throw new IllegalStateException("Invalid same-session RPC observations");
        JSONObject trace = new JSONObject(true); trace.put("schemaVersion", "1.0.2"); trace.put("traceId", first.getTraceId());
        trace.put("sourceKind", "instrumented-rpc"); trace.put("modelInputVersion", "model.input.v1");
        JSONArray frames = new JSONArray();
        for (TransportObservation observation : observations) {
            JSONObject frame = new JSONObject(true); frame.put("schemaVersion", "1.0.2"); frame.put("traceId", observation.getTraceId());
            frame.put("sessionId", observation.getSessionId()); frame.put("requestId", observation.getRequestId());
            frame.put("direction", observation.getDirection()); frame.put("encodedFrameLength", observation.getEncodedFrameLength());
            frame.put("monotonicOffsetNanos", observation.getMonotonicOffsetNanos()); frame.put("transport", observation.getTransport());
            frame.put("protocolVersion", observation.getProtocolVersion()); frames.add(frame);
        }
        trace.put("observations", frames); return trace;
    }

    private static final class JSONValue {
        private static Object parse(String value) {
            TypedValue typed = TypedValueJson.read(value);
            return com.alibaba.fastjson.JSON.parse(TypedValueJson.write(typed));
        }
    }
}
