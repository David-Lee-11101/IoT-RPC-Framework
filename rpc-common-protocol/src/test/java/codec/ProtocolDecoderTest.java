package codec;

import constants.MessageConstant;
import constants.MessageType;
import constants.SerializationType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import org.junit.Test;
import protocol.ProtocolConstruction;
import protocol.ProtocolRequest;
import serialization.JsonSerializer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ProtocolDecoderTest {

    @Test
    public void waitsForFragmentedFrameBeforeDecoding() {
        ProtocolRequest request = new ProtocolRequest();
        request.setClassName("demo.EchoService");
        request.setMethodName("echo");
        request.setParameterTypes(new Class<?>[]{String.class});
        request.setParams(new Object[]{"hello"});
        byte[] body = new JsonSerializer().serialize(request);
        ByteBuf frame = frame(body.length, body);
        ByteBuf first = frame.readRetainedSlice(MessageConstant.HEAD_TOTAL_LEN + 1);
        ByteBuf second = frame.readRetainedSlice(frame.readableBytes());
        frame.release();

        EmbeddedChannel channel = new EmbeddedChannel(new ProtocolDecoder());
        assertFalse(channel.writeInbound(first));
        assertNull(channel.readInbound());
        assertTrue(channel.writeInbound(second));

        ProtocolConstruction<?> decoded = channel.readInbound();
        assertNotNull(decoded);
        assertEquals("echo", ((ProtocolRequest) decoded.getContent()).getMethodName());
        assertNull(channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test(expected = CorruptedFrameException.class)
    public void rejectsNegativeBodyLengthBeforeAllocation() {
        EmbeddedChannel channel = new EmbeddedChannel(new ProtocolDecoder());
        try {
            channel.writeInbound(frame(-1, new byte[0]));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test(expected = TooLongFrameException.class)
    public void rejectsOversizedBodyLengthBeforeAllocation() {
        EmbeddedChannel channel = new EmbeddedChannel(new ProtocolDecoder());
        try {
            channel.writeInbound(frame(ProtocolDecoder.MAX_FRAME_LENGTH + 1, new byte[0]));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void rejectsJavaSerializationBeforeObjectDeserialization() throws Exception {
        DeserializationProbe.invocations.set(0);
        byte[] body = javaSerialized(new DeserializationProbe());
        EmbeddedChannel channel = new EmbeddedChannel(new ProtocolDecoder());
        try {
            channel.writeInbound(frame(SerializationType.JAVA_SERIALSerialization.code(), body.length, body));
            fail("Expected Java serialization to be rejected");
        } catch (CorruptedFrameException expected) {
            assertTrue(expected.getMessage().contains("JSON"));
        } finally {
            channel.pipeline().remove(ProtocolDecoder.class);
            channel.finishAndReleaseAll();
        }
        assertEquals(0, DeserializationProbe.invocations.get());
    }

    @Test
    public void rejectsUnknownSerializationBeforePayloadDispatch() {
        EmbeddedChannel channel = new EmbeddedChannel(new ProtocolDecoder());
        try {
            channel.writeInbound(frame((byte) 127, 2, new byte[]{'{', '}'}));
            fail("Expected unknown serialization to be rejected");
        } catch (CorruptedFrameException expected) {
            assertTrue(expected.getMessage().contains("JSON"));
        } finally {
            channel.pipeline().remove(ProtocolDecoder.class);
            channel.finishAndReleaseAll();
        }
    }

    private ByteBuf frame(int declaredLength, byte[] body) {
        return frame(SerializationType.JSON_Serialization.code(), declaredLength, body);
    }

    private ByteBuf frame(byte serializationType, int declaredLength, byte[] body) {
        ByteBuf frame = Unpooled.buffer(MessageConstant.HEAD_TOTAL_LEN + body.length);
        frame.writeShort(MessageConstant.MAGIC);
        frame.writeByte(serializationType);
        frame.writeByte(MessageType.REQUEST.code());
        frame.writeLong(41L);
        frame.writeInt(declaredLength);
        frame.writeBytes(body);
        return frame;
    }

    private byte[] javaSerialized(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private static class DeserializationProbe implements Serializable {
        private static final long serialVersionUID = 1L;
        private static final AtomicInteger invocations = new AtomicInteger();

        private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
            invocations.incrementAndGet();
            input.defaultReadObject();
        }
    }
}
