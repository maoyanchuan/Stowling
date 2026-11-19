package dev.stowling.automation;

/** A bounded wave of one exact item key, confirmed together by the caller. */
public final class ContainerInteractionBatch {
    public static final int MAX_TRANSFERS = 36;
    public static final int MAX_CLICKS = 64;
    private final String key;
    private int transfers;
    private int clicks;
    private int count;

    public ContainerInteractionBatch(String key) {
        this.key = key;
    }

    public boolean accepts(String itemKey, int clickCount) {
        return key.equals(itemKey) && clickCount > 0 && transfers < MAX_TRANSFERS
                && clicks + clickCount <= MAX_CLICKS;
    }

    public void record(int amount, int clickCount) {
        if (amount <= 0 || !accepts(key, clickCount)) {
            throw new IllegalArgumentException("container batch limit exceeded");
        }
        transfers++;
        clicks += clickCount;
        count += amount;
    }

    public int count() {
        return count;
    }
}
