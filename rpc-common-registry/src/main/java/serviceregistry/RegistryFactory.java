package serviceregistry;

import zookeeper.ZookeeperRegistryService;

public class RegistryFactory {

    public static IRegistryService createRegistryService(String address,RegistryType registryType){
        if (registryType == null) {
            throw new IllegalArgumentException("Registry type is required");
        }
        try {
            switch (registryType) {
                case ZOOKEEPER:
                    return new ZookeeperRegistryService(address);
                case EUREKA:
                    throw new UnsupportedOperationException("Eureka registry is not implemented");
                default:
                    throw new IllegalArgumentException("Unsupported registry type: " + registryType);
            }
        }catch (Exception e){
            throw new IllegalStateException("Unable to initialize " + registryType + " registry", e);
        }
    }
}
