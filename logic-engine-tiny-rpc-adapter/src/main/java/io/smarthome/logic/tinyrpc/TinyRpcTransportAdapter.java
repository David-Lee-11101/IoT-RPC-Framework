package io.smarthome.logic.tinyrpc;

import io.smarthome.logic.LogicModel;
import io.smarthome.logic.TransportAdapter;
import io.smarthome.logic.TypedValue;
import io.smarthome.logic.TypedValueJson;
import io.smarthome.tinyrpc.core.RpcLimits;
import io.smarthome.tinyrpc.core.RpcRequest;
import io.smarthome.tinyrpc.core.RpcResult;
import io.smarthome.tinyrpc.netty.TinyRpcClient;
import io.smarthome.tinyrpc.telemetry.TelemetryListener;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class TinyRpcTransportAdapter implements TransportAdapter, Closeable {
    private final LocalEndpointCatalog endpoints;
    private final RpcLimits limits;
    private final TelemetryListener telemetry;
    private final Map<String, ClientSession> clients = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private volatile boolean closed;
    private final String tracePrefix = "trace.logic." + java.util.UUID.randomUUID() + ".";

    public TinyRpcTransportAdapter(LocalEndpointCatalog endpoints, RpcLimits limits, TelemetryListener telemetry) {
        if (endpoints == null) throw new IllegalArgumentException("endpoint catalog is required");
        this.endpoints = endpoints; this.limits = limits == null ? RpcLimits.defaults() : limits;
        this.telemetry = telemetry == null ? TelemetryListener.NOOP : telemetry;
    }

    @Override public String kind() { return "tiny-rpc-netty"; }

    @Override public CompletableFuture<TypedValue> invoke(LogicModel.ServiceDescriptor service, String operation,
                                                           List<TypedValue> arguments, long deadlineEpochMs, String requestId) {
        if (!kind().equals(service.getTransportKind())) return failed(new IllegalArgumentException("Descriptor transport is not tiny-rpc-netty"));
        if (deadlineEpochMs <= System.currentTimeMillis()) return failed(new java.util.concurrent.TimeoutException("Invocation deadline expired"));
        LocalEndpointCatalog.Endpoint endpoint = endpoints.resolve(service.getCatalogId());
        if (endpoint == null) return failed(new IllegalStateException("No local endpoint for catalogId"));
        final ClientSession client;
        try { client = client(service.getCatalogId(), endpoint); }
        catch (Exception connection) { return failed(connection); }
        long id = sequence.incrementAndGet();
        RpcRequest request = new RpcRequest(tracePrefix + id, client.sessionId, requestId,
                service.getServiceId(), operation, TypedValueJson.writeList(arguments), deadlineEpochMs);
        CompletableFuture<RpcResult> rpc = client.transport.invoke(request);
        CompletableFuture<TypedValue> result = new CompletableFuture<>();
        rpc.whenComplete((response, failure) -> {
            if (failure != null) { result.completeExceptionally(failure); return; }
            if (!response.isSuccess()) {
                result.completeExceptionally(new InvocationFailure(response.getStatus(), response.getErrorCode())); return;
            }
            try { result.complete(TypedValueJson.read(response.getValueJson())); }
            catch (RuntimeException invalid) { result.completeExceptionally(invalid); }
        });
        result.whenComplete((value, failure) -> { if (result.isCancelled()) rpc.cancel(true); });
        return result;
    }

    private synchronized ClientSession client(String catalogId, LocalEndpointCatalog.Endpoint endpoint) throws java.io.IOException, InterruptedException {
        if (closed) throw new java.nio.channels.ClosedChannelException();
        ClientSession existing = clients.get(catalogId); if (existing != null) return existing;
        ClientSession created = new ClientSession(TinyRpcClient.connect(endpoint.host, endpoint.port, limits, telemetry));
        clients.put(catalogId, created); return created;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (ClientSession client : clients.values()) client.transport.close();
        clients.clear();
    }

    private static final class ClientSession {
        private final TinyRpcClient transport;
        private final String sessionId = "session.logic." + java.util.UUID.randomUUID();
        private ClientSession(TinyRpcClient transport) { this.transport = transport; }
    }

    private static <T> CompletableFuture<T> failed(Throwable failure) {
        CompletableFuture<T> future = new CompletableFuture<>(); future.completeExceptionally(failure); return future;
    }

    public static final class InvocationFailure extends RuntimeException {
        private final String status; private final String errorCode;
        InvocationFailure(String status, String errorCode) { super(status + ":" + errorCode); this.status = status; this.errorCode = errorCode; }
        public String getStatus() { return status; } public String getErrorCode() { return errorCode; }
    }
}
