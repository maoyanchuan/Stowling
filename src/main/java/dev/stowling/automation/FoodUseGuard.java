package dev.stowling.automation;

/** Active only while an automatic meal owns the use key. */
public final class FoodUseGuard {
    private volatile boolean blocking;

    public void begin() {
        blocking = true;
    }

    public void end() {
        blocking = false;
    }

    public boolean blocksWorldUse() {
        return blocking;
    }
}
