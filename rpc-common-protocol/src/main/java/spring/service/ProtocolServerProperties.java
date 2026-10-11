package spring.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "gp.rpc")
public class ProtocolServerProperties {

    private int servicePort;

    private String serviceAddress = "127.0.0.1";

    private byte registerType;

    private String registryAddress;
}
