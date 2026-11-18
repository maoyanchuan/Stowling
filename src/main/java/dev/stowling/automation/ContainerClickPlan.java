package dev.stowling.automation;

import java.util.ArrayList;
import java.util.List;

/** Exact two-slot transfers using whole stacks, halves, or a small remainder. */
public final class ContainerClickPlan {
    private ContainerClickPlan() {
    }

    public static List<Click> exact(int sourceCount, int amount) {
        if (amount < 1 || amount > sourceCount) {
            throw new IllegalArgumentException("invalid exact transfer amount");
        }
        if (amount == sourceCount) {
            return List.of(new Click(true, 0), new Click(false, 0));
        }
        List<Click> best = new ArrayList<>();
        best.add(new Click(true, 0));
        for (int i = 0; i < amount; i++) best.add(new Click(false, 1));
        best.add(new Click(true, 0));

        List<Click> returned = new ArrayList<>();
        returned.add(new Click(true, 0));
        for (int i = amount; i < sourceCount; i++) returned.add(new Click(true, 1));
        returned.add(new Click(false, 0));
        if (returned.size() < best.size()) best = returned;

        int half = (sourceCount + 1) / 2;
        List<Click> split = new ArrayList<>();
        split.add(new Click(true, 1));
        if (amount <= half) {
            for (int i = amount; i < half; i++) split.add(new Click(true, 1));
            split.add(new Click(false, 0));
        } else {
            split.add(new Click(false, 0));
            split.addAll(exact(sourceCount - half, amount - half));
        }
        return List.copyOf(split.size() < best.size() ? split : best);
    }

    public record Click(boolean source, int button) {
    }
}
