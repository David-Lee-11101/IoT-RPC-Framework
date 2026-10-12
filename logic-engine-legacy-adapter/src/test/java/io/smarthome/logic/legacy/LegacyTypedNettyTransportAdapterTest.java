package io.smarthome.logic.legacy;

import io.smarthome.logic.LogicModel;
import io.smarthome.logic.TypedValue;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class LegacyTypedNettyTransportAdapterTest {
    @Test public void delegatesOnlyToExplicitLegacyGateway() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LegacyTypedNettyTransportAdapter adapter = new LegacyTypedNettyTransportAdapter((service, operation, arguments, deadline, requestId) -> {
            calls.incrementAndGet(); return CompletableFuture.completedFuture(TypedValue.ofString("legacy-ok"));
        });
        LogicModel.ServiceDescriptor service = new LogicModel.ServiceDescriptor("legacy.service", "v1.0", "legacy.catalog",
                "legacy-typed-netty", "v1.0", Collections.singletonList(new LogicModel.OperationDescriptor("echo.value",
                Collections.singletonList(new LogicModel.TypeSignature(TypedValue.Type.STRING)), new LogicModel.TypeSignature(TypedValue.Type.STRING))));
        assertEquals("legacy-ok", adapter.invoke(service, "echo.value", Collections.singletonList(TypedValue.ofString("value")),
                System.currentTimeMillis() + 1000, "request-001").get().getValue());
        assertEquals(1, calls.get());
    }
}
