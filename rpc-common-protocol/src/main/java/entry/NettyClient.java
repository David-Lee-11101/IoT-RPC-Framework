package entry;

import serviceregistry.IRegistryService;
import serviceregistry.ServiceInfo;
import handler.RpcClientInitializer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;
import protocol.ProtocolConstruction;
import protocol.ProtocolRequest;
import protocol.RequestHolder;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeUnit;

@Slf4j
public class NettyClient implements Closeable {
    public static final int CONNECT_TIMEOUT_MILLIS = 3000;
    private final Bootstrap bootstrap;
    private final EventLoopGroup eventLoopGroup = new NioEventLoopGroup();
    public NettyClient(){
        log.info("开始初始化 Netty Client");
        bootstrap=new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .handler(new RpcClientInitializer());
    }

    public EventLoopGroup eventLoopGroup() {
        return eventLoopGroup;
    }

    public boolean isTerminated() {
        return eventLoopGroup.isTerminated();
    }

    public void sendRequest(ProtocolConstruction<ProtocolRequest> protocol, IRegistryService registryService) {
        long requestId = protocol.getHeader().getRequestId();
        final ServiceInfo serviceInfo;
        try {
            serviceInfo = registryService.discovery(protocol.getContent().getClassName());
            if (serviceInfo == null) {
                throw new IllegalStateException("No provider available for " + protocol.getContent().getClassName());
            }
        } catch (Exception discoveryFailure) {
            RequestHolder.fail(requestId, discoveryFailure);
            return;
        }

        ChannelFuture connectFuture = bootstrap.connect(serviceInfo.getServiceAddress(), serviceInfo.getServicePort());
        connectFuture.addListener(listener -> {
            if (!listener.isSuccess()) {
                RequestHolder.fail(requestId, listener.cause());
                return;
            }
            Channel channel = connectFuture.channel();
            channel.closeFuture().addListener(close ->
                    RequestHolder.fail(requestId, new ClosedChannelException()));
            channel.writeAndFlush(protocol).addListener(write -> {
                if (!write.isSuccess()) {
                    RequestHolder.fail(requestId, write.cause());
                    channel.close();
                }
            });
        });
    }

    @Override
    public void close() throws IOException {
        eventLoopGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
    }
}

