package io.smarthome.logic.tinyrpc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LocalEndpointCatalog {
    private final Map<String, Endpoint> endpoints = new ConcurrentHashMap<>();
    public LocalEndpointCatalog register(String catalogId, String host, int port) {
        if (catalogId == null || !catalogId.matches("[a-zA-Z][a-zA-Z0-9._:-]{2,127}")) throw new IllegalArgumentException("catalogId is invalid");
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535) throw new IllegalArgumentException("endpoint is invalid");
        if (endpoints.putIfAbsent(catalogId, new Endpoint(host, port)) != null) throw new IllegalStateException("Duplicate catalogId");
        return this;
    }
    Endpoint resolve(String catalogId) { return endpoints.get(catalogId); }
    static final class Endpoint {
        final String host; final int port;
        Endpoint(String host, int port) { this.host = host; this.port = port; }
    }
}
