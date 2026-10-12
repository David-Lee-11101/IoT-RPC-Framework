package codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import lombok.extern.slf4j.Slf4j;
import protocol.Header;
import protocol.ProtocolConstruction;
import serialization.ISerializer;
import serialization.SerializationManager;


/* Every legacy Netty frame uses this fixed header:
    +----------------------------------------------+
    | magic 2 bytes | serializer 1 byte | message type 1 byte |
    +----------------------------------------------+
    | request ID 8 bytes | payload length 4 bytes |
    +----------------------------------------------+
*/
@Slf4j
public class ProtocolEncoder extends MessageToByteEncoder<ProtocolConstruction<Object>> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ProtocolConstruction<Object> msg, ByteBuf out) throws Exception {
        log.debug("Encoding outbound RPC frame");
        Header header=msg.getHeader();
        out.writeShort(header.getMagic()); // Protocol magic.
        out.writeByte(header.getSerialType()); // Serializer identifier.
        out.writeByte(header.getReqType()); // Message type.
        out.writeLong(header.getRequestId()); // Correlation identifier.
        ISerializer serializer= SerializationManager.getSerializer(header.getSerialType());
        byte[] data=serializer.serialize(msg.getContent()); // Serialize payload.
        header.setLength(data.length);
        out.writeInt(data.length); // Payload length.
        out.writeBytes(data);
    }
}
