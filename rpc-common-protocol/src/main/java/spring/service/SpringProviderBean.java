package spring.service;

import serviceregistry.IRegistryService;
import serviceregistry.ServiceInfo;
import entry.NettyServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import spring.annotation.RemoteServiceReference;

import java.lang.reflect.Method;

@Slf4j
public class SpringProviderBean implements InitializingBean, BeanPostProcessor {

    private final int serverPort;
    private final String serverAddress;
    private final IRegistryService registryService; // Registry adapter.

    public SpringProviderBean(String serverAddress, int serverPort, IRegistryService registryService) {
        this.serverPort = serverPort;
        this.serverAddress = serverAddress;
        this.registryService = registryService; // Registry adapter.
    }

    @Override
    // Start the provider and register it during bean initialization.
    public void afterPropertiesSet() throws Exception {
        log.info("Netty server bound to host {}, port {}", this.serverAddress, this.serverPort);
        new Thread(() -> {
            try {
                new NettyServer(this.serverAddress, this.serverPort).startNettyServer();
            } catch (Exception e) {
                log.error("Netty server startup failed", e);
            }
        }).start();
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean.getClass().isAnnotationPresent(RemoteServiceReference.class)) {
            // Publish services carrying the provider annotation.
            Method[] methods = bean.getClass().getDeclaredMethods();
            for (Method method : methods) {
                // The preserved adapter expects one service interface.
                // Register each interface method in the provider allowlist.
                String serviceName = bean.getClass().getInterfaces()[0].getName();
                String key = serviceName + "." + method.getName();
                log.info("Registered provider method {}", key);
                BeanMethod beanMethod = new BeanMethod();
                beanMethod.setBean(bean);
                beanMethod.setMethod(method);
                Mediator.beanMethodMap.put(key, beanMethod);
                try {
                    ServiceInfo serviceInfo = new ServiceInfo();
                    serviceInfo.setServiceAddress(this.serverAddress);
                    serviceInfo.setServicePort(this.serverPort);
                    serviceInfo.setServiceName(serviceName);
                    registryService.register(serviceInfo);
                } catch (Exception e) {
                    log.error("Failed to register service {}", serviceName, e);
                }
            }

        }
        return bean;
    }
}
