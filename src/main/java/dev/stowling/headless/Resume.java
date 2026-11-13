package dev.stowling.headless;

import dev.stowling.control.ControlStatus;
import java.util.concurrent.Callable;

public final class Resume implements Callable<Integer> {
    public Resume() {
    }

    @Override
    public Integer call() {
        ControlStatus status = HeadlessControlBridge.requireService().resume();
        System.out.printf("Stowling paused=%s%n", status.paused());
        return 0;
    }
}
