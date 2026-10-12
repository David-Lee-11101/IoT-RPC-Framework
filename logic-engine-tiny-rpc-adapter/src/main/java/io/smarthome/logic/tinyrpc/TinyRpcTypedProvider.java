package io.smarthome.logic.tinyrpc;

import io.smarthome.logic.TypedValue;
import io.smarthome.logic.TypedValueJson;
import io.smarthome.tinyrpc.netty.TinyRpcServer;

import java.util.List;

public final class TinyRpcTypedProvider {
    private TinyRpcTypedProvider() { }
    public static TinyRpcServer.Builder register(TinyRpcServer.Builder builder, String serviceId, String operation, TypedOperation handler) {
        if (builder == null || handler == null) throw new IllegalArgumentException("builder and handler are required");
        return builder.register(serviceId, operation, argumentsJson ->
                TypedValueJson.write(handler.invoke(TypedValueJson.readList(argumentsJson))));
    }
    @FunctionalInterface public interface TypedOperation { TypedValue invoke(List<TypedValue> arguments) throws Exception; }
}
