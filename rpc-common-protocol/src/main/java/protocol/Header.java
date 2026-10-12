package protocol;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;

 /*
    +----------------------------------------------+
    | magic 2 bytes | serializer 1 byte | message type 1 byte |
    +----------------------------------------------+
    | request ID 8 bytes | payload length 4 bytes |
    +----------------------------------------------+
    */

@AllArgsConstructor
@Data
public class Header implements Serializable {
    private short magic; // Two-byte protocol identity.
    private byte serialType; // One-byte serializer identifier.
    private byte reqType; // One-byte message type.
    private long requestId; // Eight-byte correlation identifier.
    private int length; // Four-byte payload length.
}
