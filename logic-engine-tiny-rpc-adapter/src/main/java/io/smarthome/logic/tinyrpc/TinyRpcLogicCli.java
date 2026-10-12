package io.smarthome.logic.tinyrpc;

import com.alibaba.fastjson.JSONObject;
import io.smarthome.logic.TypedValue;
import io.smarthome.logic.TypedValueJson;
import io.smarthome.tinyrpc.core.RpcLimits;
import io.smarthome.tinyrpc.core.RpcRequest;
import io.smarthome.tinyrpc.core.RpcResult;
import io.smarthome.tinyrpc.netty.TinyRpcClient;
import io.smarthome.tinyrpc.netty.TinyRpcServer;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class TinyRpcLogicCli {
    private TinyRpcLogicCli() { }
    public static void main(String[] args) throws Exception {
        if (args.length == 5 && "provider".equals(args[0])) {
            runProvider(Integer.parseInt(args[1]), args[2], args[3], TypedValueJson.read(args[4])); return;
        }
        if (args.length == 6 && "invoke".equals(args[0])) {
            runInvoke(args[1], Integer.parseInt(args[2]), args[3], args[4], args[5]); return;
        }
        throw new IllegalArgumentException("Usage: provider <port|0> <serviceId> <operation> <typedValueJson> | invoke <host> <port> <serviceId> <operation> <typedArgumentsJson>");
    }

    private static void runProvider(int port, String serviceId, String operation, TypedValue result) throws Exception {
        TinyRpcServer.Builder builder = TinyRpcServer.builder();
        TinyRpcTypedProvider.register(builder, serviceId, operation, arguments -> result);
        TinyRpcServer server = builder.build().bind("127.0.0.1", port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "tiny-rpc-provider-shutdown"));
        JSONObject ready = new JSONObject(true); ready.put("event", "provider-ready"); ready.put("catalogId", "local.catalog");
        ready.put("host", "127.0.0.1"); ready.put("port", server.port()); ready.put("serviceId", serviceId); ready.put("operation", operation);
        System.out.println(ready.toJSONString()); System.out.flush();
        new CountDownLatch(1).await();
    }

    private static void runInvoke(String host, int port, String serviceId, String operation, String argumentsJson) throws Exception {
        String normalized = TypedValueJson.writeList(TypedValueJson.readList(argumentsJson));
        try (TinyRpcClient client = TinyRpcClient.connect(host, port, RpcLimits.defaults(), null)) {
            RpcResult result = client.invoke(new RpcRequest("trace.cli.001", "session.cli.001", "request.cli.001",
                    serviceId, operation, normalized, System.currentTimeMillis() + 3000)).get(4, TimeUnit.SECONDS);
            JSONObject output = new JSONObject(true); output.put("event", "invocation-result"); output.put("requestId", result.getRequestId());
            output.put("status", result.getStatus());
            if (result.isSuccess()) output.put("value", JSONValue.parse(result.getValueJson()));
            else { output.put("errorCode", result.getErrorCode()); output.put("errorMessage", result.getErrorMessage()); }
            System.out.println(output.toJSONString());
        }
    }

    private static final class JSONValue {
        private static Object parse(String value) {
            TypedValue typed = TypedValueJson.read(value);
            return com.alibaba.fastjson.JSON.parse(TypedValueJson.write(typed));
        }
    }
}
