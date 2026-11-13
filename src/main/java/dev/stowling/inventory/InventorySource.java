package dev.stowling.inventory;

import dev.stowling.domain.ContainerRef;
import java.util.concurrent.CompletableFuture;

public interface InventorySource {
    String sourceId();

    CompletableFuture<InventoryCapture> capture(ContainerRef container);
}
