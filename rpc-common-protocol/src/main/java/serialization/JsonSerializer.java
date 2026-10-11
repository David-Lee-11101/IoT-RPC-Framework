package serialization;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.parser.ParserConfig;
import constants.SerializationType;

import java.nio.charset.StandardCharsets;

public class JsonSerializer implements ISerializer{
    private static final ParserConfig PARSER_CONFIG = createParserConfig();

    private static ParserConfig createParserConfig() {
        ParserConfig config = new ParserConfig();
        config.setAutoTypeSupport(false);
        config.setSafeMode(true);
        return config;
    }

    @Override
    public <T> byte[] serialize(T obj) {
        return JSON.toJSONString(obj).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <T> T deserialize(byte[] data, Class<T> clazz) {
        return JSON.parseObject(new String(data, StandardCharsets.UTF_8), clazz, PARSER_CONFIG);
    }

    @Override
    public byte getType() {
        return SerializationType.JSON_Serialization.code();
    }
}

