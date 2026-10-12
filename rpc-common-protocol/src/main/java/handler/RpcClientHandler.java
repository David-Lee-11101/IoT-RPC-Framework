package handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;
import protocol.ProtocolConstruction;
import protocol.ProtocolResponse;
import protocol.RequestHolder;

@Slf4j
public class RpcClientHandler extends SimpleChannelInboundHandler<ProtocolConstruction<ProtocolResponse>> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ProtocolConstruction<ProtocolResponse> msg) throws Exception {
        log.info("Received an RPC response from the provider");
        // Correlate the reply with the request identifier assigned by the client.
        long requestId=msg.getHeader().getRequestId();
        if (!RequestHolder.complete(requestId, msg.getContent())) {
            log.warn("Ignoring response for unknown request ID {}", requestId);
        }
        ctx.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.fireExceptionCaught(cause);
        ctx.close();
    }
}
