package entry;

import handler.RpcServerInitializer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NettyServer{
    private String serverAddress; // Bind address.
    private int serverPort; // Bind port.

    public NettyServer(String serverAddress, int serverPort) {
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
    }

    public void startNettyServer() throws Exception {
        log.info("Starting Netty service provider");
        EventLoopGroup bossGroup=new NioEventLoopGroup();  // Accept connections.
        EventLoopGroup workGroup=new NioEventLoopGroup();  // Process channel I/O.
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new RpcServerInitializer());
            ChannelFuture channelFuture = bootstrap.bind(this.serverAddress, this.serverPort).sync();
            log.info("Netty service provider started on port {}", this.serverPort);
            channelFuture.channel().closeFuture().sync();
        }catch (Exception e){
            log.error("Netty service provider startup failed", e);
        }finally {
            workGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}

