package dev.stowling.automation;

import dev.stowling.domain.ContainerRef;
import java.util.Objects;
import java.util.Optional;

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
            int count,
            Optional<ContainerRef> sourceContainer,
            Optional<ContainerRef> destinationContainer
    ) {
        public Request {
            mode = Objects.requireNonNull(mode, "mode");
            source = requireText(source, "source");
            destination = requireText(destination, "destination");
            itemId = requireText(itemId, "itemId");
            sourceContainer = Objects.requireNonNull(
                    sourceContainer,
                    "sourceContainer"
            );
            destinationContainer = Objects.requireNonNull(
                    destinationContainer,
                    "destinationContainer"
            );
            if (count < 1) {
                throw new IllegalArgumentException("count must be positive");
            }
            validateExplicitContainers(sourceContainer, destinationContainer);
        }

        public Request(
                Mode mode,
                String source,
                String destination,
                String itemId,
                int count
        ) {
            this(
                    mode,
                    source,
                    destination,
                    itemId,
                    count,
                    Optional.empty(),
                    Optional.empty()
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

        private static void validateExplicitContainers(
                Optional<ContainerRef> sourceContainer,
                Optional<ContainerRef> destinationContainer
        ) {
            if (sourceContainer.isEmpty() || destinationContainer.isEmpty()) {
                return;
            }
            ContainerRef sourceRef = sourceContainer.orElseThrow();
            ContainerRef destinationRef = destinationContainer.orElseThrow();
            if (sourceRef.equals(destinationRef)
                    || !sourceRef.dimension().equals(destinationRef.dimension())) {
                throw new IllegalArgumentException(
                        "explicit containers must be distinct and in one dimension"
                );
            }
        }
    }
}
