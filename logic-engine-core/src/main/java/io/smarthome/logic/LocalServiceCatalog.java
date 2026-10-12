package io.smarthome.logic;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LocalServiceCatalog {
    private final Map<String, LogicModel.ServiceDescriptor> services = new ConcurrentHashMap<>();
    public LocalServiceCatalog register(LogicModel.ServiceDescriptor descriptor) {
        if (descriptor == null) throw new IllegalArgumentException("descriptor is required");
        if (!"1.0.1".equals(descriptor.getSchemaVersion())) throw new IllegalArgumentException("Unsupported descriptor schema");
        LogicModel.ServiceDescriptor previous = services.putIfAbsent(descriptor.getServiceId(), descriptor);
        if (previous != null) throw new IllegalStateException("Duplicate serviceId: " + descriptor.getServiceId());
        return this;
    }
    public LogicModel.ServiceDescriptor resolve(String serviceId) { return services.get(serviceId); }
}
