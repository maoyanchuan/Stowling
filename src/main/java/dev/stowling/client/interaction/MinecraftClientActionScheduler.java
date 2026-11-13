package dev.stowling.client.interaction;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;

public final class MinecraftClientActionScheduler implements ClientActionScheduler {
    private final Minecraft minecraft;

    public MinecraftClientActionScheduler(Minecraft minecraft) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    }

    @Override
    public boolean isClientThread() {
        return minecraft.isSameThread();
    }

    @Override
    public void execute(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (isClientThread()) {
            action.run();
        } else {
            minecraft.execute(action);
        }
    }

    @Override
    public <T> CompletableFuture<T> submit(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        if (isClientThread()) {
            try {
                return CompletableFuture.completedFuture(action.get());
            } catch (RuntimeException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }
        return minecraft.submit(action);
    }
}
