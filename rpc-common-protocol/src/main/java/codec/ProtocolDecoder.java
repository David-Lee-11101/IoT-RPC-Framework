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


/*  netty通信报文每个报文统一header格式如下：
    +----------------------------------------------+
    | 魔数 2byte | 序列化算法 1byte | 请求类型 1byte  |
    +----------------------------------------------+
    | 消息 ID 8byte     |      数据长度 4byte       |
    +----------------------------------------------+
*/
@Slf4j
public class ProtocolDecoder extends ByteToMessageDecoder {
    public static final int MAX_FRAME_LENGTH = 1024 * 1024;

    @Override
    //处理器上下文，入站数据，解码后消息对象的列表，整个列表将被传给管道中的下一个处理器
    //将字节转化为了更高级的消息对象
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        log.info("==================== 入站消息解码处理 =======================");
        if (in.readableBytes() < MessageConstant.HEAD_TOTAL_LEN) {
            //消息长度不够，不需要解析
            return;
        }
        //标记数组读取前的开头位置，防止后续数组读取失败可返回
        in.markReaderIndex();
        //使用魔数确定目标数据包
        short magic = in.readShort();
        if (magic != MessageConstant.MAGIC) {
            throw new IllegalArgumentException("魔数格式不匹配，报文错误," + magic);
        }
        //按照请求头格式读取
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
        //可读区域的字节数小于实际数据长度，说明此时接收到的数据是半包，还没有完整到达，没有足够的数据，因此等待后重新读取
        if (in.readableBytes() < dataLength) {
            in.resetReaderIndex();
            return;
        }
        //读取消息内容
        byte[] content = new byte[dataLength];
        in.readBytes(content);

        //构建header头信息
        Header header = new Header(magic, serialType, reqType, requestId, dataLength);
        ISerializer serializer = SerializationManager.getSerializer(serialType);
        MessageType type = MessageType.findByCode(reqType);
        if (type == null) {
            throw new CorruptedFrameException("Unsupported message type: " + reqType);
        }
        switch (type) {
            case REQUEST:
                //在这里反序列化，将接收到的字节数组转为规定好的RRequest格式的类对象
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
