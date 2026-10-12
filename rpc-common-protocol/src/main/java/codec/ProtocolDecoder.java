package codec;

import constants.MessageConstant;
import constants.MessageType;
import constants.SerializationType;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import lombok.extern.slf4j.Slf4j;
import protocol.Header;
import protocol.ProtocolConstruction;
import protocol.ProtocolRequest;
import protocol.ProtocolResponse;
import serialization.ISerializer;
import serialization.SerializationManager;

import java.util.List;


/* Every legacy Netty frame uses this fixed header:
    +----------------------------------------------+
    | magic 2 bytes | serializer 1 byte | message type 1 byte |
    +----------------------------------------------+
    | request ID 8 bytes | payload length 4 bytes |
    +----------------------------------------------+
*/
@Slf4j
public class ProtocolDecoder extends ByteToMessageDecoder {
    public static final int MAX_FRAME_LENGTH = 1024 * 1024;

    @Override
    // Decode inbound bytes into messages for the next pipeline handler.
    // Convert bytes into a typed protocol message.
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        log.debug("Decoding inbound RPC frame");
        if (in.readableBytes() < MessageConstant.HEAD_TOTAL_LEN) {
            // Wait for the complete fixed header.
            return;
        }
        // Mark the reader index so a fragmented payload can be retried.
        in.markReaderIndex();
        // Validate the protocol identity.
        short magic = in.readShort();
        if (magic != MessageConstant.MAGIC) {
            throw new IllegalArgumentException("Invalid protocol magic: " + magic);
        }
        // Read fields in fixed header order.
        byte serialType = in.readByte();
        if (serialType != SerializationType.JSON_Serialization.code()) {
            throw new CorruptedFrameException("Network frames support JSON serialization only: " + serialType);
        }
        byte reqType = in.readByte();
        long requestId = in.readLong();
        int dataLength = in.readInt();
        if (dataLength < 0) {
            throw new CorruptedFrameException("Negative payload length: " + dataLength);
        }
        int maxPayloadLength = MAX_FRAME_LENGTH - MessageConstant.HEAD_TOTAL_LEN;
        if (dataLength > maxPayloadLength) {
            throw new TooLongFrameException("Payload length " + dataLength + " exceeds " + maxPayloadLength);
        }
        // Reset and wait when the payload is fragmented.
        if (in.readableBytes() < dataLength) {
            in.resetReaderIndex();
            return;
        }
        // Read the complete payload.
        byte[] content = new byte[dataLength];
        in.readBytes(content);

        // Reconstruct the validated header.
        Header header = new Header(magic, serialType, reqType, requestId, dataLength);
        ISerializer serializer = SerializationManager.getSerializer(serialType);
        MessageType type = MessageType.findByCode(reqType);
        if (type == null) {
            throw new CorruptedFrameException("Unsupported message type: " + reqType);
        }
        switch (type) {
            case REQUEST:
                // Deserialize only into the message type selected by the validated header.
                ProtocolRequest request = serializer.deserialize(content, ProtocolRequest.class);
                ProtocolConstruction<ProtocolRequest> reqProtocol = new ProtocolConstruction<>();
                reqProtocol.setHeader(header);
                reqProtocol.setContent(request);
                out.add(reqProtocol);
                break;
            case RESPONSE:
                ProtocolResponse response = serializer.deserialize(content, ProtocolResponse.class);
                ProtocolConstruction<ProtocolResponse> resProtocol = new ProtocolConstruction<>();
                resProtocol.setHeader(header);
                resProtocol.setContent(response);
                out.add(resProtocol);
                break;
            case HEARTBEAT:
                break;
            default:
                throw new CorruptedFrameException("Unsupported message type: " + reqType);
        }

    }
}
