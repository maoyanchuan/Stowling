package dev.stowling.headless;

import dev.stowling.control.ControlStatus;
import java.util.concurrent.Callable;

public final class Status implements Callable<Integer> {
    public Status() {
    }

    @Override
    public Integer call() {
        ControlStatus status = HeadlessControlBridge.requireService().status();
        System.out.printf(
                "Stowling state=%s paused=%s%n",
                status.state(),
                status.paused()
        );
        return 0;
    }
}
