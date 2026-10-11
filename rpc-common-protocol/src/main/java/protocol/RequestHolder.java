package protocol;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class RequestHolder {

    private static final AtomicLong REQUEST_ID=new AtomicLong();

    private static final ConcurrentHashMap<Long, ProtocolFuture<ProtocolResponse>> REQUEST_MAP = new ConcurrentHashMap<>();

    private RequestHolder() {
    }

    public static long nextRequestId() {
        return REQUEST_ID.incrementAndGet();
    }

    public static void register(long requestId, ProtocolFuture<ProtocolResponse> future) {
        ProtocolFuture<ProtocolResponse> previous = REQUEST_MAP.putIfAbsent(requestId, future);
        if (previous != null) {
            throw new IllegalStateException("Duplicate request ID: " + requestId);
        }
        future.getPromise().addListener(done -> {
            if (done.isCancelled()) {
                REQUEST_MAP.remove(requestId, future);
            }
        });
    }

    public static boolean complete(long requestId, ProtocolResponse response) {
        ProtocolFuture<ProtocolResponse> future = REQUEST_MAP.remove(requestId);
        return future != null && future.getPromise().trySuccess(response);
    }

    public static boolean fail(long requestId, Throwable cause) {
        ProtocolFuture<ProtocolResponse> future = REQUEST_MAP.remove(requestId);
        return future != null && future.getPromise().tryFailure(cause);
    }

    public static void remove(long requestId, ProtocolFuture<ProtocolResponse> future) {
        REQUEST_MAP.remove(requestId, future);
    }

    public static int pendingCount() {
        return REQUEST_MAP.size();
    }
}
