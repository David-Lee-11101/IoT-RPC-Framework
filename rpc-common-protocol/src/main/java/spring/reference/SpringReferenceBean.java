package spring.reference;

import serviceregistry.IRegistryService;
import serviceregistry.RegistryFactory;
import serviceregistry.RegistryType;
import entry.NettyClient;
import org.springframework.beans.factory.FactoryBean;

import java.lang.reflect.Proxy;

public class SpringReferenceBean implements FactoryBean<Object> {

    private Class<?> interfaceClass;
    private Object object;

    private byte registryType;
    private String registryAddress;
    private NettyClient nettyClient;

    @Override
    public Object getObject() throws Exception {
        return object;
    }

    // Build the Netty client, discover a provider, and connect to its resolved address.
    public void init(){
        IRegistryService registryService= RegistryFactory.createRegistryService(this.registryAddress, RegistryType.findByCode(this.registryType));
        this.object= Proxy.newProxyInstance(this.interfaceClass.getClassLoader(),
                new Class<?>[]{this.interfaceClass},
                new ProtocolInvokerProxy(registryService, nettyClient));
    }

    @Override
    public Class<?> getObjectType() {
        return this.interfaceClass;
    }

    public void setInterfaceClass(Class<?> interfaceClass) {
        this.interfaceClass = interfaceClass;
    }

    public void setRegistryType(byte registryType) {
        this.registryType = registryType;
    }

    public void setRegistryAddress(String registryAddress) {
        this.registryAddress = registryAddress;
    }

    public void setNettyClient(NettyClient nettyClient) {
        this.nettyClient = nettyClient;
    }
}
