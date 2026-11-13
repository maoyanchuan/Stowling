package dev.stowling.client;

import dev.stowling.Stowling;
import java.io.IOException;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

public final class StowlingClient implements ClientModInitializer {
    private StowlingRuntime runtime;

    @Override
    public void onInitializeClient() {
        try {
            runtime = StowlingRuntime.start(Minecraft.getInstance());
        } catch (IOException | RuntimeException exception) {
            Stowling.LOGGER.error("Stowling client runtime could not start", exception);
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(client -> runtime.tick());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (runtime != null) {
                runtime.close();
                runtime = null;
            }
        });
    }
}
