package dev.stowling.v2.runtime.modules;

public interface TaskModuleExecutor {
    TaskModuleResult start();

    TaskModuleResult tick();

    void cancel();

    default void requestPauseAtSafeNode() {
    }

    default boolean pausedAtSafeNode() {
        return false;
    }

    default void resumeFromPause() {
    }

    default void requestStop() {
        cancel();
    }

    default boolean safeToStop() {
        return true;
    }
}
