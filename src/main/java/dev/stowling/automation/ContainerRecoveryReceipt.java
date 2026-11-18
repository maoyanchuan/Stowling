package dev.stowling.automation;

/** A recovery acknowledgement survives replacement of the action during interruption cleanup. */
public final class ContainerRecoveryReceipt {
    private boolean confirmed;

    public boolean confirmed() {
        return confirmed;
    }

    public void confirm(Runnable recordAcquired) {
        if (!confirmed) {
            recordAcquired.run();
            confirmed = true;
        }
    }
}
