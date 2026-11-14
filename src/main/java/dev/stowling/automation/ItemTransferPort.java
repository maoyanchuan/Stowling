package dev.stowling.automation;

import java.util.Objects;

public interface ItemTransferPort extends AutomationOperationPort<ItemTransferPort.Request> {
    enum Mode {
        TAKE,
        PUT,
        TRANSFER
    }

    record Request(
            Mode mode,
            String source,
            String destination,
            String itemId,
            int count
    ) {
        public Request {
            mode = Objects.requireNonNull(mode, "mode");
            source = requireText(source, "source");
            destination = requireText(destination, "destination");
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
