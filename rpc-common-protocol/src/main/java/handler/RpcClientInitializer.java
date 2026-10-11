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
        log.info("开始初始化 Netty Client端通信信道");
        ch.pipeline()
                //防止粘包半包，处理帧之间的间隔。 在接收数据时，LengthFieldBasedFrameDecoder 会首先检查可用字节是否足够来读取长度字段。如果可用字节不足，它会等待更多的数据。
                //在使用 LengthFieldBasedFrameDecoder 的情况下，通常不需要自己编写解码器，因为它已经提供了对基于长度字段的帧的处理。但是，你可能需要在处理完整的帧后，针对具体的业务需求编写处理帧内容的逻辑。
                .addLast(new LengthFieldBasedFrameDecoder(ProtocolDecoder.MAX_FRAME_LENGTH,12,4,0,0))
                .addLast(new LoggingHandler())
                .addLast(new ProtocolEncoder())
                .addLast(new ProtocolDecoder())
                .addLast(new RpcClientHandler());
    }
}
