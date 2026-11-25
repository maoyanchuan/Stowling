package dev.stowling.automation;

/** Active only while an opted-in automatic meal owns the use key. */
public final class FoodUseGuard {
    private volatile boolean blocking;

    public void begin(boolean enabled) {
        blocking = enabled;
    }

    public void end() {
        blocking = false;
    }

    public boolean blocksWorldUse() {
        return blocking;
    }
}
