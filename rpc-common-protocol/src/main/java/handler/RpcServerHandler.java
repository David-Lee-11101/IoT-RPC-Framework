package handler;

import constants.MessageType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import protocol.Header;
import protocol.ProtocolConstruction;
import protocol.ProtocolRequest;
import protocol.ProtocolResponse;
import spring.service.Mediator;

public class RpcServerHandler extends SimpleChannelInboundHandler<ProtocolConstruction<ProtocolRequest>> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ProtocolConstruction<ProtocolRequest> msg) throws Exception {
        ProtocolConstruction resProtocol=new ProtocolConstruction<>();
        Header header=msg.getHeader();
        // Reuse the correlation header and switch the message type to response.
        header.setReqType(MessageType.RESPONSE.code());

        // Invoke the explicitly registered provider method.
        Object result= Mediator.getInstance().processor(msg.getContent());
        resProtocol.setHeader(header);
        ProtocolResponse response=new ProtocolResponse();
        response.setData(result);
        response.setMsg("Provider completed the requested operation");
        resProtocol.setContent(response);

        // Write the response through the outbound pipeline.
        ctx.writeAndFlush(resProtocol);
    }
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ctx.close();
    }
}

