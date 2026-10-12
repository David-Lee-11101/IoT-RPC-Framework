package serviceregistry;

public interface IRegistryService {

    /**
     * Registers a service.
     * @param serviceInfo
     * @throws Exception
     */
    void register(ServiceInfo serviceInfo) throws Exception;

    /**
     * Unregisters a service.
     * @param serviceInfo
     * @throws Exception
     */
    void unRegister(ServiceInfo serviceInfo) throws Exception;

    /**
     * Discovers a service dynamically.
     * @param serviceName
     * @return
     * @throws Exception
     */
    ServiceInfo discovery(String serviceName) throws Exception;
}
