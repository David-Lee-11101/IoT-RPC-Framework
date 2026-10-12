package io.smarthome.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LogicModel {
    private static final String IDENTIFIER = "[a-zA-Z][a-zA-Z0-9._:-]{2,127}";
    private LogicModel() { }

    public static final class FieldDescriptor {
        private final String name; private final TypedValue.Type type; private final List<FieldDescriptor> fields;
        public FieldDescriptor(String name, TypedValue.Type type) { this(name, type, Collections.emptyList()); }
        public FieldDescriptor(String name, TypedValue.Type type, List<FieldDescriptor> fields) {
            this.name = name; this.type = type; this.fields = immutable(fields);
            requireFieldName(name); requireType(type); validateFields(this.fields, 1);
            if (type != TypedValue.Type.RECORD && !this.fields.isEmpty()) throw new IllegalArgumentException("Scalar fields cannot declare nested fields");
        }
        public String getName() { return name; } public TypedValue.Type getType() { return type; }
        public List<FieldDescriptor> getFields() { return fields; }
    }

    public static final class TypeSignature {
        private final TypedValue.Type type; private final List<FieldDescriptor> fields;
        public TypeSignature(TypedValue.Type type) { this(type, Collections.emptyList()); }
        public TypeSignature(TypedValue.Type type, List<FieldDescriptor> fields) {
            this.type = type; this.fields = immutable(fields);
            requireType(type); validateFields(this.fields, 1);
            if (type != TypedValue.Type.RECORD && !this.fields.isEmpty()) throw new IllegalArgumentException("Scalar signatures cannot declare fields");
        }
        public TypedValue.Type getType() { return type; } public List<FieldDescriptor> getFields() { return fields; }
    }

    public static final class OperationDescriptor {
        private final String name; private final List<TypeSignature> parameters; private final TypeSignature returns;
        public OperationDescriptor(String name, List<TypeSignature> parameters, TypeSignature returns) {
            this.name = name; this.parameters = immutable(parameters); this.returns = returns;
            requireIdentifier(name, "operation name");
            if (returns == null || this.parameters.contains(null)) throw new IllegalArgumentException("Operation signatures are required");
        }
        public String getName() { return name; } public List<TypeSignature> getParameters() { return parameters; }
        public TypeSignature getReturns() { return returns; }
    }

    public static final class ServiceDescriptor {
        private final String schemaVersion; private final String serviceId; private final String version;
        private final String catalogId; private final String transportKind; private final String protocolVersion;
        private final List<OperationDescriptor> operations;
        public ServiceDescriptor(String serviceId, String version, String catalogId, String transportKind,
                                 String protocolVersion, List<OperationDescriptor> operations) {
            this.schemaVersion = "1.0.1"; this.serviceId = serviceId; this.version = version; this.catalogId = catalogId;
            this.transportKind = transportKind; this.protocolVersion = protocolVersion; this.operations = immutable(operations);
            requireIdentifier(serviceId, "serviceId"); requireIdentifier(catalogId, "catalogId");
            if (version == null || !(version.matches("[0-9]+\\.[0-9]+\\.[0-9]+") || version.matches(IDENTIFIER)))
                throw new IllegalArgumentException("Invalid service version");
            if (protocolVersion == null || protocolVersion.isEmpty() || protocolVersion.length() > 32)
                throw new IllegalArgumentException("Invalid protocol version");
            if (!("tiny-rpc-netty".equals(transportKind) || "legacy-typed-netty".equals(transportKind)))
                throw new IllegalArgumentException("Unsupported transport kind");
            java.util.HashSet<String> operationNames = new java.util.HashSet<>();
            for (OperationDescriptor operation : this.operations) {
                if (operation == null || !operationNames.add(operation.getName())) throw new IllegalArgumentException("Operation names must be unique");
            }
        }
        public String getSchemaVersion() { return schemaVersion; } public String getServiceId() { return serviceId; }
        public String getVersion() { return version; } public String getCatalogId() { return catalogId; }
        public String getTransportKind() { return transportKind; } public String getProtocolVersion() { return protocolVersion; }
        public List<OperationDescriptor> getOperations() { return operations; }
        public OperationDescriptor operation(String name) {
            for (OperationDescriptor operation : operations) if (operation.getName().equals(name)) return operation;
            return null;
        }
    }

    public static final class Binding {
        private final TypedValue constant; private final String nodeId;
        private Binding(TypedValue constant, String nodeId) { this.constant = constant; this.nodeId = nodeId; }
        public static Binding constant(TypedValue value) {
            if (value == null) throw new IllegalArgumentException("constant value is required");
            return new Binding(value, null);
        }
        public static Binding node(String nodeId) { requireIdentifier(nodeId, "nodeId"); return new Binding(null, nodeId); }
        public boolean isConstant() { return constant != null; } public TypedValue getConstant() { return constant; }
        public String getNodeId() { return nodeId; }
    }

    public static final class Node {
        private final String nodeId; private final String kind; private final List<Binding> inputs;
        private final TypeSignature result; private final String serviceId; private final String operation;
        private final String operator; private final String field;
        public Node(String nodeId, String kind, List<Binding> inputs, TypeSignature result,
                    String serviceId, String operation, String operator, String field) {
            this.nodeId = nodeId; this.kind = kind; this.inputs = immutable(inputs); this.result = result;
            this.serviceId = serviceId; this.operation = operation; this.operator = operator; this.field = field;
            requireIdentifier(nodeId, "nodeId");
            if (kind == null || kind.isEmpty() || this.inputs.contains(null)) throw new IllegalArgumentException("Node kind and inputs are required");
        }
        public String getNodeId() { return nodeId; } public String getKind() { return kind; }
        public List<Binding> getInputs() { return inputs; } public TypeSignature getResult() { return result; }
        public String getServiceId() { return serviceId; } public String getOperation() { return operation; }
        public String getOperator() { return operator; } public String getField() { return field; }
    }

    public static final class Output {
        private final String name; private final String nodeId;
        public Output(String name, String nodeId) {
            requireIdentifier(name, "output name"); requireIdentifier(nodeId, "nodeId");
            this.name = name; this.nodeId = nodeId;
        }
        public String getName() { return name; } public String getNodeId() { return nodeId; }
    }

    public static final class Definition {
        private final String schemaVersion = "1.0.1"; private final String graphId; private final List<Node> nodes;
        private final List<Output> outputs; private final long graphTimeoutMs; private final long nodeTimeoutMs;
        public Definition(String graphId, List<Node> nodes, List<Output> outputs, long graphTimeoutMs, long nodeTimeoutMs) {
            this.graphId = graphId; this.nodes = immutable(nodes); this.outputs = immutable(outputs);
            this.graphTimeoutMs = graphTimeoutMs; this.nodeTimeoutMs = nodeTimeoutMs;
            requireIdentifier(graphId, "graphId");
            if (this.nodes.contains(null) || this.outputs.contains(null)) throw new IllegalArgumentException("Nodes and outputs are required");
        }
        public String getSchemaVersion() { return schemaVersion; } public String getGraphId() { return graphId; }
        public List<Node> getNodes() { return nodes; } public List<Output> getOutputs() { return outputs; }
        public long getGraphTimeoutMs() { return graphTimeoutMs; } public long getNodeTimeoutMs() { return nodeTimeoutMs; }
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values == null ? Collections.emptyList() : values));
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || !value.matches(IDENTIFIER)) throw new IllegalArgumentException(name + " must be an opaque contract identifier");
    }
    private static void requireFieldName(String value) {
        if (value == null || !value.matches("[a-zA-Z][a-zA-Z0-9._-]{0,63}")) throw new IllegalArgumentException("Invalid field name");
    }
    private static void requireType(TypedValue.Type type) {
        if (type == null) throw new IllegalArgumentException("type is required");
    }
    private static void validateFields(List<FieldDescriptor> fields, int depth) {
        if (fields.size() > 32) throw new IllegalArgumentException("Record signatures support at most 32 fields");
        java.util.HashSet<String> names = new java.util.HashSet<>();
        for (FieldDescriptor field : fields) {
            if (field == null || !names.add(field.getName())) throw new IllegalArgumentException("Field names must be unique");
            if (field.getType() == TypedValue.Type.RECORD) {
                if (depth >= 4 && !field.getFields().isEmpty()) throw new IllegalArgumentException("Record signature nesting exceeds four levels");
                validateFields(field.getFields(), depth + 1);
            }
        }
    }
}
