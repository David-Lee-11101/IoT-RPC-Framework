package protocol;

import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.DefaultPromise;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RequestHolderTest {
    private DefaultEventExecutor executor;

    @Before
    public void setUp() {
        executor = new DefaultEventExecutor();
    }

    @After
    public void tearDown() {
        executor.shutdownGracefully().syncUninterruptibly();
        assertEquals(0, RequestHolder.pendingCount());
    }

    @Test
    public void completesAndRemovesCorrelation() throws Exception {
        long requestId = RequestHolder.nextRequestId();
        ProtocolFuture<ProtocolResponse> future = future();
        ProtocolResponse response = new ProtocolResponse();
        response.setData("ok");
        RequestHolder.register(requestId, future);

        assertTrue(RequestHolder.complete(requestId, response));
        assertSame(response, future.getPromise().get(1, TimeUnit.SECONDS));
        assertFalse(RequestHolder.complete(requestId, response));
    }

    @Test
    public void cancellationRemovesCorrelation() {
        long requestId = RequestHolder.nextRequestId();
        ProtocolFuture<ProtocolResponse> future = future();
        RequestHolder.register(requestId, future);

        assertTrue(future.getPromise().cancel(false));
        executor.submit(() -> { }).syncUninterruptibly();

        assertEquals(0, RequestHolder.pendingCount());
    }

    @Test
    public void generatesUniqueIdsConcurrently() throws Exception {
        int threadCount = 8;
        int idsPerThread = 250;
        Set<Long> ids = java.util.Collections.synchronizedSet(new HashSet<>());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        for (int thread = 0; thread < threadCount; thread++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < idsPerThread; i++) {
                        ids.add(RequestHolder.nextRequestId());
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertEquals(threadCount * idsPerThread, ids.size());
    }

    private ProtocolFuture<ProtocolResponse> future() {
        return new ProtocolFuture<>(new DefaultPromise<>(executor));
    }
}
