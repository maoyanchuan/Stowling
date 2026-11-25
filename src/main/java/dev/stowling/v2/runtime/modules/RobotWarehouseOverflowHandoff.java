package dev.stowling.v2.runtime.modules;

import java.util.function.BooleanSupplier;

/** Allows the opt-in total overflow stage to inspect confirmed warehouse leftovers. */
final class RobotWarehouseOverflowHandoff implements TaskModuleExecutor {
    private final TaskModuleExecutor warehouse;
    private final BooleanSupplier resourcesRecovered;
    private TaskModuleResult terminal;
    private boolean stopping;

    RobotWarehouseOverflowHandoff(TaskModuleExecutor warehouse,
            BooleanSupplier resourcesRecovered) {
        this.warehouse = warehouse;
        this.resourcesRecovered = resourcesRecovered;
    }

    @Override
    public TaskModuleResult start() {
        return terminal == null ? accept(warehouse.start()) : terminal;
    }

    @Override
    public TaskModuleResult tick() {
        return terminal == null ? accept(warehouse.tick()) : terminal;
    }

    private TaskModuleResult accept(TaskModuleResult result) {
        if (result.state() == TaskModuleState.FAILED && !stopping && warehouse.safeToStop()
                && resourcesRecovered.getAsBoolean()) {
            warehouse.cancel();
            terminal = TaskModuleResult.succeeded("机器人仓库未能接收余量，继续总溢出；"
                    + result.message());
            return terminal;
        }
        if (result.state() == TaskModuleState.FAILED
                || result.state() == TaskModuleState.SUCCEEDED) terminal = result;
        return result;
    }

    @Override
    public void requestStop() {
        stopping = true;
        warehouse.requestStop();
    }

    @Override
    public boolean safeToStop() {
        return warehouse.safeToStop();
    }

    @Override
    public void cancel() {
        stopping = true;
        warehouse.cancel();
    }
}
