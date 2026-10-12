package io.smarthome.logic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class LogicExecutionResult {
    private final String graphId; private final Map<String, TypedValue> outputs; private final Map<String, TypedValue> nodes;
    public LogicExecutionResult(String graphId, Map<String, TypedValue> outputs, Map<String, TypedValue> nodes) {
        this.graphId = graphId; this.outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
    }
    public String getGraphId() { return graphId; } public Map<String, TypedValue> getOutputs() { return outputs; }
    public Map<String, TypedValue> getNodes() { return nodes; }
}
