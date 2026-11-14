package dev.stowling.automation;

import java.util.Objects;
import java.util.Optional;

public interface PickupWorkflowPort extends AutomationOperationPort<PickupWorkflowPort.Request> {
    record Request(String workflowId, Optional<String> regionId) {
        public Request {
            workflowId = requireText(workflowId, "workflowId");
            regionId = Optional.ofNullable(regionId).orElse(Optional.empty());
            if (regionId.isPresent()) {
                regionId = Optional.of(requireText(
                        regionId.orElseThrow(),
                        "regionId"
                ));
            }
        }

        private static String requireText(String value, String fieldName) {
            Objects.requireNonNull(value, fieldName);
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException(fieldName + " must not be blank");
            }
            return trimmed;
        }
    }
}
