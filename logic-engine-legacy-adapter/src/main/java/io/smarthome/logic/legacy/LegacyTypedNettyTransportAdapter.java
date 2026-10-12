package io.smarthome.logic.legacy;

import io.smarthome.logic.LogicModel;
import io.smarthome.logic.TransportAdapter;
import io.smarthome.logic.TypedValue;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Explicit bridge to the preserved typed Netty stack. The bridge accepts an allowlisted gateway so the
 * Logic Engine never performs reflective class loading or method selection.
 */
public final class LegacyTypedNettyTransportAdapter implements TransportAdapter {
    private final Gateway gateway;
    public LegacyTypedNettyTransportAdapter(Gateway gateway) {
        if (gateway == null) throw new IllegalArgumentException("gateway is required"); this.gateway = gateway;
    }
    @Override public String kind() { return "legacy-typed-netty"; }
    @Override public CompletableFuture<TypedValue> invoke(LogicModel.ServiceDescriptor service, String operation,
                                                           List<TypedValue> arguments, long deadlineEpochMs, String requestId) {
        if (!kind().equals(service.getTransportKind())) return failed(new IllegalArgumentException("Descriptor transport is not legacy-typed-netty"));
        if (deadlineEpochMs <= System.currentTimeMillis()) return failed(new java.util.concurrent.TimeoutException("Invocation deadline expired"));
        try { return gateway.invoke(service, operation, arguments, deadlineEpochMs, requestId); }
        catch (Exception failure) { return failed(failure); }
    }
    @FunctionalInterface public interface Gateway {
        CompletableFuture<TypedValue> invoke(LogicModel.ServiceDescriptor service, String operation,
                                             List<TypedValue> arguments, long deadlineEpochMs, String requestId) throws Exception;
    }
    private static <T> CompletableFuture<T> failed(Throwable failure) {
        CompletableFuture<T> result = new CompletableFuture<>(); result.completeExceptionally(failure); return result;
    }
}
