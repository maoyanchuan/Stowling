package dev.stowling.headless;

import dev.stowling.control.ControlStatus;
import java.util.concurrent.Callable;

public final class Pause implements Callable<Integer> {
    public Pause() {
    }

    @Override
    public Integer call() {
        ControlStatus status = HeadlessControlBridge.requireService().pause();
        System.out.printf("Stowling paused=%s%n", status.paused());
        return 0;
    }
}
