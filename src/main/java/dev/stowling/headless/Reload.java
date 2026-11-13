package dev.stowling.headless;

import dev.stowling.control.ConfigReloadResult;
import java.util.concurrent.Callable;

public final class Reload implements Callable<Integer> {
    public Reload() {
    }

    @Override
    public Integer call() {
        ConfigReloadResult result = HeadlessControlBridge.requireService().reloadConfig();
        System.out.printf("Stowling config reloaded=%s%n", result.reloaded());
        return result.reloaded() ? 0 : 1;
    }
}
