package protocol;

import lombok.Data;

import java.io.Serializable;

@Data
public class ProtocolRequest implements Serializable {
    private String className;
    private String methodName;
    // Preserve ordered multi-argument calls.
    private Object[] params;
    private Class<?>[] parameterTypes;
}
