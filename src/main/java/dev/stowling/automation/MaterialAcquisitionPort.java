package dev.stowling.automation;

import java.util.Objects;

public interface MaterialAcquisitionPort
        extends AutomationOperationPort<MaterialAcquisitionPort.Request> {
    record Request(String itemId, int count) {
        public Request {
            itemId = requireText(itemId, "itemId");
            if (count < 1) {
                throw new IllegalArgumentException("count must be positive");
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
