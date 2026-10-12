package handler;

import codec.ProtocolDecoder;
import codec.ProtocolEncoder;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.logging.LoggingHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RpcClientInitializer extends ChannelInitializer<SocketChannel> {
    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        log.info("Initializing Netty client channel");
        ch.pipeline()
                // Decode complete frames only after the fixed header and declared payload are available.
                // The protocol decoder validates the complete frame after length-field framing.
                .addLast(new LengthFieldBasedFrameDecoder(ProtocolDecoder.MAX_FRAME_LENGTH,12,4,0,0))
                .addLast(new LoggingHandler())
                .addLast(new ProtocolEncoder())
                .addLast(new ProtocolDecoder())
                .addLast(new RpcClientHandler());
    }
}
