package dev.stowling.automation;

import java.util.Objects;

/**
 * Reserved boundary for server-confirmed warehouse synchronization.
 *
 * <p>No in-process implementation is registered yet. Callers must not infer
 * success without an explicit confirmed operation result.
 */
public interface WarehouseSyncExecutionPort
        extends AutomationOperationPort<WarehouseSyncExecutionPort.Request> {
    record Request(String warehouseId) {
        public Request {
            Objects.requireNonNull(warehouseId, "warehouseId");
            warehouseId = warehouseId.trim();
            if (warehouseId.isEmpty()) {
                throw new IllegalArgumentException(
                        "warehouseId must not be blank"
                );
            }
        }
    }
}
