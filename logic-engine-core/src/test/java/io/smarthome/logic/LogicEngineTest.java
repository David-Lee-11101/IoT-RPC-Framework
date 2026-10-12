package io.smarthome.logic;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LogicEngineTest {
    @Test public void acceptsFrozenHubVersionAndProtocolLabels() {
        LogicModel.ServiceDescriptor descriptor = new LogicModel.ServiceDescriptor("sensor.service", "1.0.0", "local.catalog",
                "tiny-rpc-netty", "tiny-rpc/1", Collections.emptyList());
        assertEquals("1.0.0", descriptor.getVersion());
        assertEquals("tiny-rpc/1", descriptor.getProtocolVersion());
    }
    private static final LogicModel.TypeSignature NUMBER = new LogicModel.TypeSignature(TypedValue.Type.NUMBER);
    private static final LogicModel.TypeSignature BOOLEAN = new LogicModel.TypeSignature(TypedValue.Type.BOOLEAN);
    private static final LogicModel.TypeSignature STRING = new LogicModel.TypeSignature(TypedValue.Type.STRING);
    private static final LogicModel.TypeSignature SENSOR = new LogicModel.TypeSignature(TypedValue.Type.RECORD, Arrays.asList(
            new LogicModel.FieldDescriptor("temperature", TypedValue.Type.NUMBER),
            new LogicModel.FieldDescriptor("mode", TypedValue.Type.STRING)));

    @Test public void executesTypedRecordSelectionAndComparisonDeterministically() {
        CountingAdapter adapter = new CountingAdapter(sensor(23.5, "comfort"));
        LogicEngine engine = engine(adapter);
        LogicModel.Definition graph = graph(Arrays.asList(
                serviceNode("sensor.read"),
                node("threshold.value", "constant", Collections.singletonList(LogicModel.Binding.constant(TypedValue.ofNumber(22))), NUMBER, null, null, null, null),
                node("temperature.pick", "select-field", Collections.singletonList(LogicModel.Binding.node("sensor.read")), NUMBER, null, null, null, "temperature"),
                node("comfort.check", "compare", Arrays.asList(LogicModel.Binding.node("temperature.pick"), LogicModel.Binding.node("threshold.value")), BOOLEAN, null, null, "gt", null)
        ), Collections.singletonList(new LogicModel.Output("comfort.result", "comfort.check")));

        List<LogicModel.Node> order = engine.validate(graph);
        assertEquals(Arrays.asList("sensor.read", "temperature.pick", "threshold.value", "comfort.check"),
                Arrays.asList(order.get(0).getNodeId(), order.get(1).getNodeId(), order.get(2).getNodeId(), order.get(3).getNodeId()));
        assertTrue(engine.execute(graph).getOutputs().get("comfort.result").asBoolean());
        assertEquals(1, adapter.calls.get());
    }

    @Test public void rejectsCyclesAndMissingReferencesBeforeTransport() {
        CountingAdapter adapter = new CountingAdapter(TypedValue.ofNumber(1));
        LogicEngine engine = engine(adapter);
        expect("logic.cycle", () -> engine.execute(graph(Arrays.asList(
                node("node.one", "arithmetic", Arrays.asList(LogicModel.Binding.node("node.two"), LogicModel.Binding.constant(TypedValue.ofNumber(1))), NUMBER, null, null, "add", null),
                node("node.two", "arithmetic", Arrays.asList(LogicModel.Binding.node("node.one"), LogicModel.Binding.constant(TypedValue.ofNumber(1))), NUMBER, null, null, "add", null)
        ), Collections.emptyList())));
        expect("logic.missing-node", () -> engine.execute(graph(Collections.singletonList(
                node("node.one", "boolean", Arrays.asList(LogicModel.Binding.node("absent.node"), LogicModel.Binding.constant(TypedValue.ofBoolean(true))), BOOLEAN, null, null, "and", null)
        ), Collections.emptyList())));
        assertEquals(0, adapter.calls.get());
    }

    @Test public void rejectsUnknownServiceOperationAndSignatureBeforeTransport() {
        CountingAdapter adapter = new CountingAdapter(sensor(20, "idle"));
        LogicEngine engine = engine(adapter);
        expect("logic.unknown-service", () -> engine.execute(graph(Collections.singletonList(
                node("call.node", "service-call", Collections.emptyList(), SENSOR, "missing.service", "read.state", null, null)), Collections.emptyList())));
        expect("logic.unknown-operation", () -> engine.execute(graph(Collections.singletonList(
                node("call.node", "service-call", Collections.emptyList(), SENSOR, "sensor.service", "missing.op", null, null)), Collections.emptyList())));
        expect("logic.signature", () -> engine.execute(graph(Collections.singletonList(
                node("call.node", "service-call", Collections.singletonList(LogicModel.Binding.constant(TypedValue.ofNumber(1))), SENSOR, "sensor.service", "read.state", null, null)), Collections.emptyList())));
        expect("logic.signature", () -> engine.execute(graph(Collections.singletonList(
                node("call.node", "service-call", Collections.emptyList(), STRING, "sensor.service", "read.state", null, null)), Collections.emptyList())));
        assertEquals(0, adapter.calls.get());
    }

    @Test public void rejectsSelectionContractErrorsBeforeTransport() {
        CountingAdapter adapter = new CountingAdapter(sensor(20, "idle"));
        LogicEngine engine = engine(adapter);
        expect("logic.select-source", () -> engine.execute(graph(Collections.singletonList(
                node("select.one", "select-field", Collections.singletonList(LogicModel.Binding.constant(sensor(20, "idle"))), NUMBER, null, null, null, "temperature")), Collections.emptyList())));
        expect("logic.missing-field", () -> engine.execute(graph(Arrays.asList(serviceNode("sensor.read"),
                node("select.one", "select-field", Collections.singletonList(LogicModel.Binding.node("sensor.read")), NUMBER, null, null, null, "humidity")), Collections.emptyList())));
        expect("logic.field-type-mismatch", () -> engine.execute(graph(Arrays.asList(serviceNode("sensor.read"),
                node("select.one", "select-field", Collections.singletonList(LogicModel.Binding.node("sensor.read")), STRING, null, null, null, "temperature")), Collections.emptyList())));
        assertEquals(0, adapter.calls.get());
    }

    @Test public void rejectsOperatorTypeAndConstantDivisionErrorsBeforeTransport() {
        LogicEngine engine = engine(new CountingAdapter(sensor(20, "idle")));
        expect("logic.unknown-kind", () -> engine.execute(graph(Collections.singletonList(
                node("unsafe.node", "java-class", Collections.emptyList(), STRING, null, null, "java.lang.Runtime", null)), Collections.emptyList())));
        expect("logic.operator", () -> engine.execute(graph(Collections.singletonList(
                node("unsafe.node", "arithmetic", Arrays.asList(LogicModel.Binding.constant(TypedValue.ofNumber(1)), LogicModel.Binding.constant(TypedValue.ofNumber(2))), NUMBER, null, null, "eval", null)), Collections.emptyList())));
        expect("logic.input-type", () -> engine.execute(graph(Collections.singletonList(
                node("bad.compare", "compare", Arrays.asList(LogicModel.Binding.constant(TypedValue.ofString("1")), LogicModel.Binding.constant(TypedValue.ofNumber(1))), BOOLEAN, null, null, "gt", null)), Collections.emptyList())));
        expect("logic.divide-by-zero", () -> engine.execute(graph(Collections.singletonList(
                node("bad.divide", "arithmetic", Arrays.asList(LogicModel.Binding.constant(TypedValue.ofNumber(1)), LogicModel.Binding.constant(TypedValue.ofNumber(0))), NUMBER, null, null, "divide", null)), Collections.emptyList())));
    }

    @Test public void reportsRuntimeMissingAndMismatchedSelectedFields() {
        Map<String, TypedValue> missing = new LinkedHashMap<>(); missing.put("mode", TypedValue.ofString("idle"));
        LogicModel.Definition graph = graph(Arrays.asList(serviceNode("sensor.read"),
                node("select.one", "select-field", Collections.singletonList(LogicModel.Binding.node("sensor.read")), NUMBER, null, null, null, "temperature")),
                Collections.singletonList(new LogicModel.Output("result.value", "select.one")));
        expect("logic.missing-field", () -> engine(new CountingAdapter(TypedValue.record(missing))).execute(graph));
        Map<String, TypedValue> wrong = new LinkedHashMap<>(); wrong.put("temperature", TypedValue.ofString("hot")); wrong.put("mode", TypedValue.ofString("idle"));
        expect("logic.field-type-mismatch", () -> engine(new CountingAdapter(TypedValue.record(wrong))).execute(graph));
    }

    @Test public void enforcesNodeDeadlineAndBoundedArithmetic() {
        CountingAdapter never = new CountingAdapter(null);
        never.future = new CompletableFuture<>();
        expect("logic.node-timeout", () -> engine(never).execute(new LogicModel.Definition("graph.timeout",
                Collections.singletonList(serviceNode("sensor.read")), Collections.emptyList(), 100, 10)));
        assertTrue(never.future.isCancelled());
        expect("logic.number-bound", () -> engine(new CountingAdapter(null)).execute(graph(Collections.singletonList(
                node("math.node", "arithmetic", Arrays.asList(LogicModel.Binding.constant(TypedValue.ofNumber(1_000_000_000_000D)), LogicModel.Binding.constant(TypedValue.ofNumber(2))), NUMBER, null, null, "multiply", null)),
                Collections.singletonList(new LogicModel.Output("math.result", "math.node")))));
    }

    @Test public void enforcesRecordFieldAndDepthLimits() {
        Map<String, TypedValue> fields = new LinkedHashMap<>();
        for (int i = 0; i < 33; i++) fields.put("field" + i, TypedValue.ofInteger(i));
        expectIllegal(() -> TypedValue.record(fields));
        TypedValue levelFour = record("four", TypedValue.ofString("ok"));
        TypedValue levelThree = record("three", levelFour);
        TypedValue levelTwo = record("two", levelThree);
        TypedValue levelOne = record("one", levelTwo);
        assertEquals(TypedValue.Type.RECORD, levelOne.getType());
        expectIllegal(() -> record("zero", levelOne));
        expectIllegal(() -> new LogicModel.TypeSignature(TypedValue.Type.STRING,
                Collections.singletonList(new LogicModel.FieldDescriptor("invalid", TypedValue.Type.STRING))));
    }

    private static LogicEngine engine(CountingAdapter adapter) {
        LogicModel.OperationDescriptor operation = new LogicModel.OperationDescriptor("read.state", Collections.emptyList(), SENSOR);
        LogicModel.ServiceDescriptor service = new LogicModel.ServiceDescriptor("sensor.service", "v1.0", "local.catalog",
                "tiny-rpc-netty", "v1.0", Collections.singletonList(operation));
        return new LogicEngine(new LocalServiceCatalog().register(service), Collections.singletonList(adapter));
    }

    private static LogicModel.Node serviceNode(String id) {
        return node(id, "service-call", Collections.emptyList(), SENSOR, "sensor.service", "read.state", null, null);
    }
    private static LogicModel.Node node(String id, String kind, List<LogicModel.Binding> inputs, LogicModel.TypeSignature result,
                                        String service, String operation, String operator, String field) {
        return new LogicModel.Node(id, kind, inputs, result, service, operation, operator, field);
    }
    private static LogicModel.Definition graph(List<LogicModel.Node> nodes, List<LogicModel.Output> outputs) {
        return new LogicModel.Definition("graph.demo", nodes, outputs, 1000, 200);
    }
    private static TypedValue sensor(double temperature, String mode) {
        Map<String, TypedValue> fields = new LinkedHashMap<>(); fields.put("temperature", TypedValue.ofNumber(temperature)); fields.put("mode", TypedValue.ofString(mode));
        return TypedValue.record(fields);
    }
    private static TypedValue record(String name, TypedValue value) {
        Map<String, TypedValue> fields = new LinkedHashMap<>(); fields.put(name, value); return TypedValue.record(fields);
    }
    private static void expect(String code, Runnable action) {
        try { action.run(); fail("Expected " + code); }
        catch (LogicFailure failure) { assertEquals(code, failure.getCode()); }
    }
    private static void expectIllegal(Runnable action) {
        try { action.run(); fail("Expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }

    private static final class CountingAdapter implements TransportAdapter {
        private final AtomicInteger calls = new AtomicInteger();
        private CompletableFuture<TypedValue> future;
        private CountingAdapter(TypedValue result) { this.future = CompletableFuture.completedFuture(result); }
        @Override public String kind() { return "tiny-rpc-netty"; }
        @Override public CompletableFuture<TypedValue> invoke(LogicModel.ServiceDescriptor service, String operation,
                                                               List<TypedValue> arguments, long deadlineEpochMs, String requestId) {
            calls.incrementAndGet(); return future;
        }
    }
}
