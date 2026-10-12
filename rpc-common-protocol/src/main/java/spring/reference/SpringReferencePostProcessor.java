package spring.reference;

import constants.MessageConstant;
import entry.NettyClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import spring.annotation.RemoteCallReference;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class SpringReferencePostProcessor implements ApplicationContextAware, BeanClassLoaderAware, BeanFactoryPostProcessor {
    private ApplicationContext context;
    private ClassLoader classLoader;
    private ProtocolClientProperties clientProperties;
    private final NettyClient nettyClient;

    public SpringReferencePostProcessor(ProtocolClientProperties clientProperties, NettyClient nettyClient) {
        this.clientProperties = clientProperties;
        this.nettyClient = nettyClient;
    }

    // Store published reference bean definitions.
    private final Map<String, BeanDefinition> rpcRefBeanDefinitions=new ConcurrentHashMap<>();

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader=classLoader;
    }
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.context=applicationContext;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        for (String beanDefinitionname:beanFactory.getBeanDefinitionNames()){
            // Inspect loaded bean fields for RemoteCallReference.
            // Build a dynamic proxy for each annotated reference.
            BeanDefinition beanDefinition=beanFactory.getBeanDefinition(beanDefinitionname);
            String beanClassName=beanDefinition.getBeanClassName();
            if(beanClassName!=null){
                // Resolve the declared reference interface.
                Class<?> clazz= ClassUtils.resolveClassName(beanClassName,this.classLoader);
                ReflectionUtils.doWithFields(clazz,this::parseRpcReference);
            }
        }
        // Replace an annotated reference with its dynamic proxy.
        BeanDefinitionRegistry registry=(BeanDefinitionRegistry)beanFactory;
        this.rpcRefBeanDefinitions.forEach((beanName,beanDefinition)->{
            if(context.containsBean(beanName)){
                log.warn("SpringContext already register bean {}",beanName);
                return;
            }
            registry.registerBeanDefinition(beanName,beanDefinition);
            log.info("Registered bean method {}", beanName);
        });
    }
    private void parseRpcReference(Field field){
        // Check whether the bean class declares RemoteCallReference.
        RemoteCallReference remoteCallReference= AnnotationUtils.getAnnotation(field,RemoteCallReference.class);
        // The annotation marks this bean as an RPC reference.
        if(remoteCallReference!=null) {
            BeanDefinitionBuilder builder=BeanDefinitionBuilder.genericBeanDefinition(SpringReferenceBean.class);
            // Invoke the reference bean initialization method after registration.
            builder.setInitMethodName(MessageConstant.INIT_METHOD_NAME);
            builder.addPropertyValue("interfaceClass",field.getType());
            // Resolve the proxy by its interface type.
            log.info("Creating Spring RPC proxy for interface {}", field.getType());
            builder.addPropertyValue("registryType",clientProperties.getRegistryType());
            builder.addPropertyValue("registryAddress",clientProperties.getRegistryAddress());
            builder.addPropertyValue("nettyClient", nettyClient);
            BeanDefinition beanDefinition=builder.getBeanDefinition();
            rpcRefBeanDefinitions.put(field.getName(),beanDefinition);
            log.info("Created Spring RPC client reference");
        }
    }
}
