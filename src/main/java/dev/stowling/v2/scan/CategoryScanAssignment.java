package dev.stowling.v2.scan;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Maximum-evidence one-to-one display assignment; tied optima remain editable ambiguities. */
final class CategoryScanAssignment {
    record Choice(BlockPosition anchor, AreaBounds area, long score, Set<Integer> slices) {
        Choice(BlockPosition anchor, AreaBounds area, long score) {
            this(anchor, area, score, Set.of());
        }
    }

    record Result(List<Choice> choices, Set<Integer> ambiguous) {
    }

    private record Match(List<Choice> choices, long score) {
    }

    private CategoryScanAssignment() {
    }

    static Result assign(List<List<Choice>> choices) {
        Choice[] selected = new Choice[choices.size()];
        Set<Integer> ambiguous = new HashSet<>();
        // Test only connected sets of competing groups; distant parts of a large mother do not
        // require another matching pass when checking a local tie.
        for (var component : components(choices)) {
            List<List<Choice>> local = component.stream().map(choices::get).toList();
            var optimal = match(local, -1, null);
            if (optimal == null) {
                for (int i = 0; i < component.size(); i++) {
                    selected[component.get(i)] = local.get(i).getFirst();
                    ambiguous.add(component.get(i));
                }
                continue;
            }
            for (int i = 0; i < local.size(); i++) {
                selected[component.get(i)] = optimal.choices().get(i);
                if (local.get(i).size() < 2) continue;
                var other = match(local, i, optimal.choices().get(i).anchor());
                if (other != null && other.score() == optimal.score()) {
                    ambiguous.add(component.get(i));
                }
            }
        }
        return new Result(List.of(selected), Set.copyOf(ambiguous));
    }

    private static List<List<Integer>> components(List<List<Choice>> choices) {
        Map<BlockPosition, List<Integer>> owners = new HashMap<>();
        for (int i = 0; i < choices.size(); i++) {
            for (var choice : choices.get(i)) {
                owners.computeIfAbsent(choice.anchor(), ignored -> new ArrayList<>()).add(i);
            }
        }
        Set<Integer> visited = new HashSet<>();
        List<List<Integer>> result = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++) {
            if (!visited.add(i)) continue;
            var queue = new ArrayDeque<Integer>();
            var component = new ArrayList<Integer>();
            queue.add(i);
            while (!queue.isEmpty()) {
                int group = queue.remove();
                component.add(group);
                for (var choice : choices.get(group)) {
                    for (int owner : owners.get(choice.anchor())) {
                        if (visited.add(owner)) queue.add(owner);
                    }
                }
            }
            result.add(component);
        }
        return result;
    }

    private static Match match(List<List<Choice>> groups, int excludedGroup,
            BlockPosition excludedAnchor) {
        Map<BlockPosition, Integer> slots = new LinkedHashMap<>();
        for (var group : groups) {
            for (var choice : group) slots.computeIfAbsent(choice.anchor(), ignored -> slots.size());
        }
        int[] owners = new int[slots.size()];
        Arrays.fill(owners, -1);
        Choice[] chosen = new Choice[groups.size()];
        int[] chosenSlots = new int[groups.size()];
        Arrays.fill(chosenSlots, -1);
        for (int start = 0; start < groups.size(); start++) {
            long[] distance = new long[groups.size()];
            Arrays.fill(distance, Long.MAX_VALUE);
            distance[start] = 0;
            long[] slotDistance = new long[slots.size()];
            Arrays.fill(slotDistance, Long.MAX_VALUE);
            int[] previous = new int[slots.size()];
            Choice[] edges = new Choice[slots.size()];
            boolean[] queued = new boolean[groups.size()];
            var queue = new ArrayDeque<Integer>();
            queue.add(start);
            queued[start] = true;
            while (!queue.isEmpty()) {
                int group = queue.remove();
                queued[group] = false;
                for (var choice : groups.get(group)) {
                    if (group == excludedGroup && choice.anchor().equals(excludedAnchor)) continue;
                    int slot = slots.get(choice.anchor());
                    if (chosenSlots[group] == slot) continue;
                    long cost = distance[group] - choice.score();
                    if (cost >= slotDistance[slot]) continue;
                    slotDistance[slot] = cost;
                    previous[slot] = group;
                    edges[slot] = choice;
                    int owner = owners[slot];
                    if (owner >= 0 && cost + chosen[owner].score() < distance[owner]) {
                        distance[owner] = cost + chosen[owner].score();
                        if (!queued[owner]) { queue.add(owner); queued[owner] = true; }
                    }
                }
            }
            int end = -1;
            for (int slot = 0; slot < owners.length; slot++) {
                if (owners[slot] < 0 && slotDistance[slot] != Long.MAX_VALUE
                        && (end < 0 || slotDistance[slot] < slotDistance[end])) end = slot;
            }
            if (end < 0) return null;
            while (end >= 0) {
                int group = previous[end];
                int prior = chosenSlots[group];
                chosen[group] = edges[end];
                chosenSlots[group] = end;
                owners[end] = group;
                end = prior;
            }
        }
        return new Match(List.of(chosen), Arrays.stream(chosen).mapToLong(Choice::score).sum());
    }
}
