package io.smarthome.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;

public final class LogicEngine {
    private static final double MAX_ABSOLUTE_NUMBER = 1_000_000_000_000D;
    private final LocalServiceCatalog catalog;
    private final Map<String, TransportAdapter> adapters = new HashMap<>();

    public LogicEngine(LocalServiceCatalog catalog, List<TransportAdapter> adapters) {
        if (catalog == null || adapters == null) throw new IllegalArgumentException("catalog and adapters are required");
        this.catalog = catalog;
        for (TransportAdapter adapter : adapters) {
            if (adapter == null || adapter.kind() == null) throw new IllegalArgumentException("Transport adapter and kind are required");
            if (this.adapters.put(adapter.kind(), adapter) != null) throw new IllegalArgumentException("Duplicate transport adapter");
        }
    }

    public LogicExecutionResult execute(LogicModel.Definition definition) {
        List<LogicModel.Node> order = validate(definition);
        long graphDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(definition.getGraphTimeoutMs());
        Map<String, TypedValue> values = new LinkedHashMap<>();
        for (LogicModel.Node node : order) {
            if (System.nanoTime() >= graphDeadline) throw failure("logic.graph-timeout", node, "Graph deadline exceeded");
            values.put(node.getNodeId(), executeNode(node, values, definition.getNodeTimeoutMs(), graphDeadline));
        }
        Map<String, TypedValue> outputs = new LinkedHashMap<>();
        Map<String, LogicModel.Node> nodes = new HashMap<>();
        for (LogicModel.Node node : definition.getNodes()) nodes.put(node.getNodeId(), node);
        for (LogicModel.Output output : definition.getOutputs()) {
            LogicModel.Node node = nodes.get(output.getNodeId());
            TypedValue value = values.get(output.getNodeId());
            requireValueMatches(value, node.getResult(), node, "logic.result-type");
            outputs.put(output.getName(), value);
        }
        return new LogicExecutionResult(definition.getGraphId(), outputs, values);
    }

    public List<LogicModel.Node> validate(LogicModel.Definition definition) {
        if (definition == null || !"1.0.1".equals(definition.getSchemaVersion())) throw new LogicFailure("logic.schema", null, "Unsupported logic schema");
        if (definition.getGraphTimeoutMs() < 1 || definition.getGraphTimeoutMs() > 60_000
                || definition.getNodeTimeoutMs() < 1 || definition.getNodeTimeoutMs() > definition.getGraphTimeoutMs())
            throw new LogicFailure("logic.timeout-policy", null, "Invalid timeout policy");
        Map<String, LogicModel.Node> byId = new HashMap<>();
        for (LogicModel.Node node : definition.getNodes()) {
            if (node.getNodeId() == null || byId.put(node.getNodeId(), node) != null)
                throw failure("logic.duplicate-node", node, "Node IDs must be unique");
        }
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (LogicModel.Node node : definition.getNodes()) {
            indegree.put(node.getNodeId(), 0); dependents.put(node.getNodeId(), new ArrayList<>());
        }
        for (LogicModel.Node node : definition.getNodes()) {
            for (LogicModel.Binding input : node.getInputs()) if (!input.isConstant()) {
                LogicModel.Node source = byId.get(input.getNodeId());
                if (source == null) throw failure("logic.missing-node", node, "Input references a missing node");
                indegree.put(node.getNodeId(), indegree.get(node.getNodeId()) + 1);
                dependents.get(source.getNodeId()).add(node.getNodeId());
            }
            validateNode(node, byId);
        }
        for (LogicModel.Output output : definition.getOutputs()) if (!byId.containsKey(output.getNodeId()))
            throw new LogicFailure("logic.missing-output", output.getNodeId(), "Output references a missing node");
        PriorityQueue<String> ready = new PriorityQueue<>();
        indegree.forEach((id, degree) -> { if (degree == 0) ready.add(id); });
        List<LogicModel.Node> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.remove(); order.add(byId.get(id));
            for (String dependent : dependents.get(id)) {
                int degree = indegree.get(dependent) - 1; indegree.put(dependent, degree); if (degree == 0) ready.add(dependent);
            }
        }
        if (order.size() != definition.getNodes().size()) throw new LogicFailure("logic.cycle", null, "Logic graph contains a cycle");
        return Collections.unmodifiableList(order);
    }

    private void validateNode(LogicModel.Node node, Map<String, LogicModel.Node> byId) {
        if (node.getResult() == null) throw failure("logic.result-type", node, "Node result signature is required");
        switch (node.getKind()) {
            case "constant":
                requireInputs(node, 1); if (!node.getInputs().get(0).isConstant()) throw failure("logic.binding", node, "Constant node requires constant binding");
                requireValueMatches(node.getInputs().get(0).getConstant(), node.getResult(), node, "logic.constant-type"); return;
            case "service-call": validateServiceCall(node, byId); return;
            case "select-field": validateSelect(node, byId); return;
            case "compare": requireInputs(node, 2); requireKnownOperator(node, "gt", "gte", "lt", "lte", "eq", "ne");
                requireComparableInputs(node, byId);
                if (node.getResult().getType() != TypedValue.Type.BOOLEAN) throw failure("logic.result-type", node, "Comparison must return boolean"); return;
            case "boolean": requireKnownOperator(node, "and", "or", "not"); requireInputs(node, "not".equals(node.getOperator()) ? 1 : 2);
                requireInputTypes(node, byId, TypedValue.Type.BOOLEAN); if (node.getResult().getType() != TypedValue.Type.BOOLEAN) throw failure("logic.result-type", node, "Boolean operation must return boolean"); return;
            case "arithmetic": requireInputs(node, 2); requireKnownOperator(node, "add", "subtract", "multiply", "divide");
                requireNumericInputs(node, byId); if (node.getResult().getType() != TypedValue.Type.NUMBER) throw failure("logic.result-type", node, "Arithmetic returns number");
                if ("divide".equals(node.getOperator()) && node.getInputs().get(1).isConstant()
                        && node.getInputs().get(1).getConstant().asNumber() == 0D)
                    throw failure("logic.divide-by-zero", node, "Division by zero");
                return;
            default: throw failure("logic.unknown-kind", node, "Unsupported node kind");
        }
    }

    private void validateServiceCall(LogicModel.Node node, Map<String, LogicModel.Node> byId) {
        LogicModel.ServiceDescriptor service = catalog.resolve(node.getServiceId());
        if (service == null) throw failure("logic.unknown-service", node, "Service is not registered");
        LogicModel.OperationDescriptor operation = service.operation(node.getOperation());
        if (operation == null) throw failure("logic.unknown-operation", node, "Operation is not registered");
        if (operation.getParameters().size() != node.getInputs().size()) throw failure("logic.signature", node, "Argument count mismatch");
        for (int index = 0; index < node.getInputs().size(); index++) {
            LogicModel.TypeSignature actual = bindingType(node.getInputs().get(index), byId);
            if (!same(actual, operation.getParameters().get(index))) throw failure("logic.signature", node, "Argument signature mismatch");
        }
        if (!same(node.getResult(), operation.getReturns())) throw failure("logic.signature", node, "Result signature mismatch");
        if (!adapters.containsKey(service.getTransportKind())) throw failure("logic.transport", node, "Transport adapter is not registered");
    }

    private void validateSelect(LogicModel.Node node, Map<String, LogicModel.Node> byId) {
        requireInputs(node, 1); LogicModel.Binding input = node.getInputs().get(0);
        if (input.isConstant()) throw failure("logic.select-source", node, "select-field requires one node output");
        LogicModel.TypeSignature source = byId.get(input.getNodeId()).getResult();
        if (source.getType() != TypedValue.Type.RECORD) throw failure("logic.select-source", node, "select-field source must be a record");
        LogicModel.FieldDescriptor field = field(source.getFields(), node.getField());
        if (field == null) throw failure("logic.missing-field", node, "Selected field is not declared");
        if (!same(node.getResult(), new LogicModel.TypeSignature(field.getType(), field.getFields())))
            throw failure("logic.field-type-mismatch", node, "Selected field signature does not match node result");
    }

    private TypedValue executeNode(LogicModel.Node node, Map<String, TypedValue> values, long nodeTimeoutMs, long graphDeadline) {
        List<TypedValue> inputs = resolve(node, values);
        switch (node.getKind()) {
            case "constant": return inputs.get(0);
            case "service-call":
                LogicModel.ServiceDescriptor service = catalog.resolve(node.getServiceId());
                LogicModel.OperationDescriptor operation = service.operation(node.getOperation());
                for (int index = 0; index < inputs.size(); index++)
                    requireValueMatches(inputs.get(index), operation.getParameters().get(index), node, "logic.signature");
                TransportAdapter adapter = adapters.get(service.getTransportKind());
                long remainingMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(graphDeadline - System.nanoTime()));
                long timeoutMs = Math.min(nodeTimeoutMs, remainingMs);
                try {
                    TypedValue result = adapter.invoke(service, node.getOperation(), inputs,
                            System.currentTimeMillis() + timeoutMs, "request-" + node.getNodeId()).get(timeoutMs, TimeUnit.MILLISECONDS);
                    if (result == null || result.getType() != node.getResult().getType())
                        throw failure("logic.result-type", node, "Runtime value does not match declared outer type");
                    if (result.getType() != TypedValue.Type.RECORD) requireValueMatches(result, node.getResult(), node, "logic.result-type");
                    return result;
                } catch (java.util.concurrent.TimeoutException timeout) { throw failure("logic.node-timeout", node, "Service call timed out"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw failure("logic.transport-failure", node, "InterruptedException"); }
                catch (java.util.concurrent.ExecutionException transport) {
                    Throwable cause = transport.getCause();
                    if (cause instanceof java.util.concurrent.TimeoutException) throw failure("logic.node-timeout", node, "Service call timed out");
                    throw failure("logic.transport-failure", node, cause == null ? "ExecutionException" : cause.getClass().getSimpleName());
                }
            case "select-field":
                TypedValue selected = inputs.get(0).asRecord().get(node.getField());
                if (selected == null) throw failure("logic.missing-field", node, "Record does not contain selected field");
                try { requireValueMatches(selected, node.getResult(), node, "logic.field-type-mismatch"); }
                catch (LogicFailure mismatch) { throw mismatch; }
                return selected;
            case "compare": return compare(node, inputs);
            case "boolean": return bool(node, inputs);
            case "arithmetic": return arithmetic(node, inputs);
            default: throw failure("logic.unknown-kind", node, "Unsupported node kind");
        }
    }

    private static List<TypedValue> resolve(LogicModel.Node node, Map<String, TypedValue> values) {
        List<TypedValue> result = new ArrayList<>();
        for (LogicModel.Binding input : node.getInputs()) result.add(input.isConstant() ? input.getConstant() : values.get(input.getNodeId()));
        return result;
    }
    private static TypedValue compare(LogicModel.Node node, List<TypedValue> values) {
        TypedValue left = values.get(0), right = values.get(1); boolean result;
        if (numeric(left.getType()) && numeric(right.getType())) {
            double a = left.asNumber(), b = right.asNumber();
            switch (node.getOperator()) { case "gt": result=a>b; break; case "gte": result=a>=b; break; case "lt": result=a<b; break; case "lte": result=a<=b; break; case "eq": result=Double.compare(a,b)==0; break; case "ne": result=Double.compare(a,b)!=0; break; default: throw failure("logic.operator", node, "Unsupported comparison"); }
        } else {
            if (left.getType() != right.getType() || !("eq".equals(node.getOperator()) || "ne".equals(node.getOperator())))
                throw failure("logic.input-type", node, "Comparison types are incompatible");
            result = left.getValue() == null ? right.getValue() == null : left.getValue().equals(right.getValue());
            if ("ne".equals(node.getOperator())) result = !result;
        }
        return TypedValue.ofBoolean(result);
    }
    private static TypedValue bool(LogicModel.Node node, List<TypedValue> values) {
        boolean result; switch (node.getOperator()) { case "and": result=values.get(0).asBoolean()&&values.get(1).asBoolean(); break; case "or": result=values.get(0).asBoolean()||values.get(1).asBoolean(); break; case "not": result=!values.get(0).asBoolean(); break; default: throw failure("logic.operator", node, "Unsupported boolean operator"); }
        return TypedValue.ofBoolean(result);
    }
    private static TypedValue arithmetic(LogicModel.Node node, List<TypedValue> values) {
        double left=values.get(0).asNumber(), right=values.get(1).asNumber(), result;
        switch (node.getOperator()) { case "add": result=left+right; break; case "subtract": result=left-right; break; case "multiply": result=left*right; break; case "divide": if (right==0D) throw failure("logic.divide-by-zero", node, "Division by zero"); result=left/right; break; default: throw failure("logic.operator", node, "Unsupported arithmetic operator"); }
        if (!Double.isFinite(result) || Math.abs(result)>MAX_ABSOLUTE_NUMBER) throw failure("logic.number-bound", node, "Numeric result is not finite or exceeds bounds");
        return TypedValue.ofNumber(result);
    }

    private static LogicModel.TypeSignature bindingType(LogicModel.Binding binding, Map<String, LogicModel.Node> byId) {
        return binding.isConstant() ? signature(binding.getConstant()) : byId.get(binding.getNodeId()).getResult();
    }
    private static LogicModel.TypeSignature signature(TypedValue value) {
        if (value.getType()!=TypedValue.Type.RECORD) return new LogicModel.TypeSignature(value.getType());
        List<LogicModel.FieldDescriptor> fields=new ArrayList<>(); value.asRecord().forEach((name,member)->fields.add(new LogicModel.FieldDescriptor(name,member.getType(),signature(member).getFields())));
        return new LogicModel.TypeSignature(TypedValue.Type.RECORD,fields);
    }
    private static boolean same(LogicModel.TypeSignature left, LogicModel.TypeSignature right) {
        if (left==null||right==null||left.getType()!=right.getType()||left.getFields().size()!=right.getFields().size()) return false;
        for (int i=0;i<left.getFields().size();i++) { LogicModel.FieldDescriptor a=left.getFields().get(i), b=right.getFields().get(i); if (!a.getName().equals(b.getName())||a.getType()!=b.getType()||!same(new LogicModel.TypeSignature(a.getType(),a.getFields()),new LogicModel.TypeSignature(b.getType(),b.getFields()))) return false; }
        return true;
    }
    private static LogicModel.FieldDescriptor field(List<LogicModel.FieldDescriptor> fields,String name){for(LogicModel.FieldDescriptor field:fields)if(field.getName().equals(name))return field;return null;}
    private static void requireInputs(LogicModel.Node node,int count){if(node.getInputs().size()!=count)throw failure("logic.input-count",node,"Unexpected input count");}
    private static void requireKnownOperator(LogicModel.Node node,String...allowed){for(String value:allowed)if(value.equals(node.getOperator()))return;throw failure("logic.operator",node,"Unsupported operator");}
    private static void requireInputTypes(LogicModel.Node node,Map<String,LogicModel.Node>byId,TypedValue.Type type){for(LogicModel.Binding input:node.getInputs())if(bindingType(input,byId).getType()!=type)throw failure("logic.input-type",node,"Input type mismatch");}
    private static void requireNumericInputs(LogicModel.Node node,Map<String,LogicModel.Node>byId){for(LogicModel.Binding input:node.getInputs())if(!numeric(bindingType(input,byId).getType()))throw failure("logic.input-type",node,"Numeric input required");}
    private static void requireComparableInputs(LogicModel.Node node,Map<String,LogicModel.Node>byId){
        LogicModel.TypeSignature left=bindingType(node.getInputs().get(0),byId), right=bindingType(node.getInputs().get(1),byId);
        boolean numericPair=numeric(left.getType())&&numeric(right.getType());
        boolean equality=("eq".equals(node.getOperator())||"ne".equals(node.getOperator()))&&same(left,right);
        if(!numericPair&&!equality)throw failure("logic.input-type",node,"Comparison types are incompatible");
    }
    private static boolean numeric(TypedValue.Type type){return type==TypedValue.Type.INTEGER||type==TypedValue.Type.NUMBER;}
    private static void requireValueMatches(TypedValue value,LogicModel.TypeSignature signature,LogicModel.Node node,String code){if(!same(signature(value),signature))throw failure(code,node,"Runtime value does not match declared signature");}
    private static LogicFailure failure(String code,LogicModel.Node node,String message){return new LogicFailure(code,node==null?null:node.getNodeId(),message);}
}
