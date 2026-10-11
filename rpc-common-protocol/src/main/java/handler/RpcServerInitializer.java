package handler;

import codec.ProtocolDecoder;
import codec.ProtocolEncoder;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.logging.LoggingHandler;

public class RpcServerInitializer extends ChannelInitializer<SocketChannel> {
    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ch.pipeline()
                .addLast(new LengthFieldBasedFrameDecoder(ProtocolDecoder.MAX_FRAME_LENGTH,12,4,0,0))
                .addLast(new LoggingHandler())
                .addLast(new ProtocolDecoder())
                .addLast(new ProtocolEncoder())
                .addLast(new RpcServerHandler());
    }
}
