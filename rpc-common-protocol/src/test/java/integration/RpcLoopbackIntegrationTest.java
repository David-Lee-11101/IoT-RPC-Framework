package integration;

import entry.NettyClient;
import handler.RpcServerInitializer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.apache.curator.test.TestingServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import protocol.RequestHolder;
import serviceregistry.ServiceInfo;
import spring.reference.ProtocolInvokerProxy;
import spring.service.BeanMethod;
import spring.service.Mediator;
import zookeeper.ZookeeperRegistryService;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RpcLoopbackIntegrationTest {
    private TestingServer testingServer;
    private ZookeeperRegistryService registry;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel providerChannel;
    private NettyClient client;
    private EchoService proxy;

    @Before
    public void startLoopbackStack() throws Exception {
        testingServer = new TestingServer(true);
        registry = new ZookeeperRegistryService(testingServer.getConnectString());

        EchoService implementation = new EchoServiceImpl();
        for (Method method : EchoService.class.getMethods()) {
            BeanMethod beanMethod = new BeanMethod();
            beanMethod.setBean(implementation);
            beanMethod.setMethod(method);
            Mediator.beanMethodMap.put(EchoService.class.getName() + "." + method.getName(), beanMethod);
        }

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(2);
        providerChannel = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new RpcServerInitializer())
                .bind("127.0.0.1", 0)
                .sync()
                .channel();

        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.setServiceName(EchoService.class.getName());
        serviceInfo.setServiceAddress("127.0.0.1");
        serviceInfo.setServicePort(((InetSocketAddress) providerChannel.localAddress()).getPort());
        registry.register(serviceInfo);
        awaitProviderRegistration();

        client = new NettyClient();
        proxy = (EchoService) Proxy.newProxyInstance(
                EchoService.class.getClassLoader(),
                new Class<?>[]{EchoService.class},
                new ProtocolInvokerProxy(registry, client, 250));
    }

    @After
    public void stopLoopbackStack() throws Exception {
        if (providerChannel != null) {
            providerChannel.close().syncUninterruptibly();
        }
        if (client != null) {
            client.close();
            assertTrue(client.isTerminated());
        }
        if (registry != null) {
            registry.close();
        }
        if (testingServer != null) {
            testingServer.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }
        Mediator.beanMethodMap.clear();
        assertEquals(0, RequestHolder.pendingCount());
    }

    @Test
    public void correlatesRapidConcurrentResponsesAndTransportsRuleSpecJson() throws Exception {
        assertEquals("rapid", proxy.echo("rapid"));

        String ruleSpec = loadRuleSpecPayload();
        assertEquals(ruleSpec, proxy.echo(ruleSpec));

        ExecutorService callers = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                final String expected = "request-" + index;
                results.add(callers.submit(() -> proxy.echo(expected)));
            }
            for (int index = 0; index < results.size(); index++) {
                assertEquals("request-" + index, results.get(index).get(3, TimeUnit.SECONDS));
            }
        } finally {
            callers.shutdownNow();
            callers.awaitTermination(2, TimeUnit.SECONDS);
        }
        assertEquals(0, RequestHolder.pendingCount());
    }

    @Test
    public void rejectsAlteredFixtureWhenDigestIsPinned() throws Exception {
        Path altered = Files.createTempFile("altered-rulespec-", ".json");
        String previousFixture = System.getProperty("rulespec.fixture");
        String previousDigest = System.getProperty("rulespec.fixture.sha256");
        try {
            Files.write(altered, "{\"id\":\"different\"}".getBytes(StandardCharsets.UTF_8));
            System.setProperty("rulespec.fixture", altered.toString());
            System.setProperty("rulespec.fixture.sha256",
                    "0000000000000000000000000000000000000000000000000000000000000000");
            try {
                loadRuleSpecPayload();
                fail("Expected a digest mismatch");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("digest"));
            }
        } finally {
            restoreProperty("rulespec.fixture", previousFixture);
            restoreProperty("rulespec.fixture.sha256", previousDigest);
            Files.deleteIfExists(altered);
        }
    }

    @Test
    public void timeoutAndDisconnectAlwaysClearCorrelations() throws Exception {
        try {
            proxy.slowEcho("late");
            fail("Expected request timeout");
        } catch (Throwable failure) {
            Throwable root = rootCause(failure);
            assertTrue("Expected TimeoutException but got " + root, root instanceof TimeoutException);
        }
        awaitNoPendingRequests();

        try {
            proxy.disconnect("close");
            fail("Expected provider disconnect");
        } catch (Throwable failure) {
            Throwable root = rootCause(failure);
            assertTrue("Expected ClosedChannelException but got " + root, root instanceof ClosedChannelException);
        }
        awaitNoPendingRequests();
    }

    private void awaitNoPendingRequests() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (RequestHolder.pendingCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(0, RequestHolder.pendingCount());
    }

    private void awaitProviderRegistration() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        ServiceInfo registeredProvider = null;
        while (registeredProvider == null && System.nanoTime() < deadline) {
            registeredProvider = registry.discovery(EchoService.class.getName());
            if (registeredProvider == null) {
                Thread.sleep(20);
            }
        }
        assertNotNull("Provider registration was not visible", registeredProvider);
    }

    private Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String loadRuleSpecPayload() throws Exception {
        String fixturePath = System.getProperty("rulespec.fixture");
        if (fixturePath == null || fixturePath.trim().isEmpty()) {
            return "{\"actions\":[{\"device\":\"notification\",\"state\":\"sent\"}],"
                    + "\"conditions\":[],\"description\":\"RPC string echo fixture\",\"id\":\"rpc-echo\","
                    + "\"triggers\":[{\"device\":\"button\",\"state\":\"pressed\"}]}";
        }
        byte[] payload = Files.readAllBytes(Paths.get(fixturePath));
        String expectedDigest = System.getProperty("rulespec.fixture.sha256");
        if (expectedDigest == null || expectedDigest.trim().isEmpty()) {
            throw new IllegalArgumentException("rulespec.fixture.sha256 is required with rulespec.fixture");
        }
        String actualDigest = sha256(payload);
        if (!expectedDigest.equals(actualDigest)) {
            throw new IllegalArgumentException("RuleSpec fixture digest mismatch");
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    private String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            result.append(String.format("%02x", item & 0xff));
        }
        return result.toString();
    }

    private void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    public interface EchoService {
        String echo(String value);
        String slowEcho(String value);
        String disconnect(String value);
    }

    public static class EchoServiceImpl implements EchoService {
        @Override
        public String echo(String value) {
            return value;
        }

        @Override
        public String slowEcho(String value) {
            try {
                Thread.sleep(750);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted", interrupted);
            }
            return value;
        }

        @Override
        public String disconnect(String value) {
            throw new IllegalStateException("intentional disconnect");
        }
    }
}
