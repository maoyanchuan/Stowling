package dev.stowling.v2.runtime.modules;

public interface TaskModuleExecutor {
    TaskModuleResult start();

    TaskModuleResult tick();

    void cancel();

    default void requestStop() {
        cancel();
    }

    default boolean safeToStop() {
        return true;
    }
}
