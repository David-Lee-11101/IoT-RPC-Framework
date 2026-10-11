package serialization;

import java.util.concurrent.ConcurrentHashMap;

public class SerializationManager {

    private final static ConcurrentHashMap<Byte, ISerializer> serializers=new ConcurrentHashMap<Byte, ISerializer>();

    static {
        ISerializer jsonSerializer=new JsonSerializer();
        serializers.put(jsonSerializer.getType(),jsonSerializer);
    }

    public static ISerializer getSerializer(byte key){
        ISerializer serializer=serializers.get(key);
        if(serializer==null){
            throw new IllegalArgumentException("Unsupported serializer type: " + key);
        }
        return serializer;
    }
}

