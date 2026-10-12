package io.smarthome.logic;

public final class LogicFailure extends RuntimeException {
    private final String code; private final String nodeId;
    public LogicFailure(String code, String nodeId, String message) { super(message); this.code = code; this.nodeId = nodeId; }
    public String getCode() { return code; } public String getNodeId() { return nodeId; }
}
