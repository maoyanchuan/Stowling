package dev.stowling.automation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/** Separates missing classifications from classifications whose every target is full. */
public final class ClassificationRemainderPlan {
    private ClassificationRemainderPlan() {
    }

    public static Set<String> select(Map<String, List<ContainerTarget>> routes,
            boolean unclassifiable, BiPredicate<ContainerTarget, String> full) {
        Set<String> result = new LinkedHashSet<>();
        routes.forEach((key, targets) -> {
            if (unclassifiable ? targets.isEmpty()
                    : !targets.isEmpty()
                            && targets.stream().allMatch(target -> full.test(target, key))) {
                result.add(key);
            }
        });
        return Set.copyOf(result);
    }
}
