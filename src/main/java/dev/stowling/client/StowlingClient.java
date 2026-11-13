package dev.stowling.client;

import net.fabricmc.api.ClientModInitializer;

public final class StowlingClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Client-only automation services are wired in the Fabric automation phase.
    }
}
