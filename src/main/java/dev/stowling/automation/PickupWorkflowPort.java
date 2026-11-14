package dev.stowling.automation;

import java.util.Objects;
import java.util.Optional;

public interface PickupWorkflowPort extends AutomationOperationPort<PickupWorkflowPort.Request> {
    record Request(
            String workflowId,
            Optional<String> regionId,
            boolean waitForItems,
            Optional<CustomPickupRules> customRules
    ) {
        public Request(String workflowId, Optional<String> regionId) {
            this(workflowId, regionId, false);
        }

        public Request(String workflowId, Optional<String> regionId, boolean waitForItems) {
            this(workflowId, regionId, waitForItems, Optional.empty());
        }

        public Request {
            customRules = Objects.requireNonNull(customRules, "customRules");
            regionId = Optional.ofNullable(regionId).orElse(Optional.empty());
            if (customRules.isPresent()) {
                if ((workflowId != null && !workflowId.isBlank()) || regionId.isPresent()) {
                    throw new IllegalArgumentException(
                            "custom pickup rules cannot reference a legacy workflow or region"
                    );
                }
                workflowId = "";
            } else {
                workflowId = requireText(workflowId, "workflowId");
                if (regionId.isPresent()) {
                    regionId = Optional.of(requireText(regionId.orElseThrow(), "regionId"));
                }
            }
        }

        public static Request custom(CustomPickupRules rules, boolean waitForItems) {
            return new Request(
                    "", Optional.empty(), waitForItems, Optional.of(rules)
            );
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
