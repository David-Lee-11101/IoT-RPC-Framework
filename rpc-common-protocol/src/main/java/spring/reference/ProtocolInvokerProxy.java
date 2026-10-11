package spring.reference;

import serviceregistry.IRegistryService;
import constants.MessageConstant;
import constants.MessageType;
import constants.SerializationType;
import entry.NettyClient;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.concurrent.DefaultPromise;
import lombok.extern.slf4j.Slf4j;
import protocol.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
public class ProtocolInvokerProxy implements InvocationHandler {
    static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 5000;

    private final IRegistryService registryService;
    private final NettyClient nettyClient;
    private final long requestTimeoutMillis;

    public ProtocolInvokerProxy(IRegistryService registryService, NettyClient nettyClient) {
        this(registryService, nettyClient, DEFAULT_REQUEST_TIMEOUT_MILLIS);
    }

    public ProtocolInvokerProxy(IRegistryService registryService, NettyClient nettyClient,
                                long requestTimeoutMillis) {
        if (requestTimeoutMillis <= 0) {
            throw new IllegalArgumentException("requestTimeoutMillis must be positive");
        }
        this.registryService = registryService;
        this.nettyClient = nettyClient;
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    //可以使用监听器或者CompletableFuture+addListener的方法，对结果的处理以thenApply的方式采取异步回调
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        log.info("开始远程调用Service Provider的函数");
        // 组装参数
        ProtocolConstruction<ProtocolRequest> protocol = new ProtocolConstruction<>();
        long requestId = RequestHolder.nextRequestId();
        Header header = new Header(MessageConstant.MAGIC, SerializationType.JSON_Serialization.code(), MessageType.REQUEST.code(), requestId, 0);
        protocol.setHeader(header);
        ProtocolRequest request = new ProtocolRequest();
        request.setClassName(method.getDeclaringClass().getName());
        request.setMethodName(method.getName());
        request.setParameterTypes(method.getParameterTypes());
        request.setParams(args);
        protocol.setContent(request);

        ProtocolFuture<ProtocolResponse> future = new ProtocolFuture<>(
                new DefaultPromise<>(nettyClient.eventLoopGroup().next()));
        RequestHolder.register(requestId, future);
        ScheduledFuture<?> timeout = nettyClient.eventLoopGroup().next().schedule(
                () -> RequestHolder.fail(requestId,
                        new TimeoutException("RPC request timed out after " + requestTimeoutMillis + " ms")),
                requestTimeoutMillis,
                TimeUnit.MILLISECONDS);
        future.getPromise().addListener(done -> timeout.cancel(false));
        nettyClient.sendRequest(protocol, registryService);

        try {
            return future.getPromise().get().getData();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            RequestHolder.fail(requestId, interrupted);
            throw interrupted;
        } catch (ExecutionException failed) {
            throw failed.getCause();
        } finally {
            RequestHolder.remove(requestId, future);
        }
    }

}
