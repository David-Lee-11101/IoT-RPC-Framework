package protocol;

import io.netty.util.concurrent.Promise;
import lombok.Data;

@Data
public class ProtocolFuture<T> {
    // Netty Promise extends Future with completion of asynchronous I/O.
    private Promise<T> promise;
    public ProtocolFuture(Promise<T> promise) {
        this.promise = promise;
    }
}