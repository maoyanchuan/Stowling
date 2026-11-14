package dev.stowling.automation;

import dev.stowling.domain.ContainerRef;
import java.util.List;
import java.util.Objects;

public interface PackingOperationPort
        extends AutomationOperationPort<PackingOperationPort.Request> {
    record Request(
            TaskPackingPlan plan,
            ContainerRef source,
            List<ItemAmount> items
    ) {
        public Request {
            plan = Objects.requireNonNull(plan, "plan");
            source = Objects.requireNonNull(source, "source");
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            if (items.isEmpty()) {
                throw new IllegalArgumentException("packing items must not be empty");
            }
            if (!source.dimension().equals(plan.route().bulkContainer().dimension())) {
                throw new IllegalArgumentException(
                        "packing source must share the task plan dimension"
                );
            }
        }
    }
}
