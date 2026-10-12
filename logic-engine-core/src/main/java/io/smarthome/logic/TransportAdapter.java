package io.smarthome.logic;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface TransportAdapter {
    String kind();
    CompletableFuture<TypedValue> invoke(LogicModel.ServiceDescriptor service, String operation,
                                         List<TypedValue> arguments, long deadlineEpochMs, String requestId);
}
