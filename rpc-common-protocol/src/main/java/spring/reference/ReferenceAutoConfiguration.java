package spring.reference;

import entry.NettyClient;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class ReferenceAutoConfiguration implements EnvironmentAware {

    @Bean(destroyMethod = "close")
    public NettyClient rpcNettyClient() {
        return new NettyClient();
    }

    @Bean
    public SpringReferencePostProcessor postProcessor(NettyClient nettyClient){
        ProtocolClientProperties rc=new ProtocolClientProperties();

        rc.setRegistryType(Byte.parseByte(environment.getProperty("registryType")));
        rc.setRegistryAddress(environment.getProperty("registryAddress"));
        return new SpringReferencePostProcessor(rc, nettyClient);
    }

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment=environment;
    }
}
