package spring.reference;


import lombok.Data;

@Data
public class ProtocolClientProperties {
    // Registry discovery supersedes direct endpoint configuration.
    private byte registryType;

    private String registryAddress;
}
