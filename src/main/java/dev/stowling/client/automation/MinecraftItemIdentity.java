package dev.stowling.client.automation;

import com.google.common.collect.MapMaker;
import dev.stowling.automation.ItemIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

public final class MinecraftItemIdentity {
    // Keep a key while inventories or tasks reference it, then release its component snapshot.
    private static final ConcurrentMap<Variant, String> VARIANT_KEYS =
            new MapMaker().weakValues().makeMap();
    private static final String SESSION =
            Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36);
    private static final AtomicLong NEXT_ID = new AtomicLong();

    private MinecraftItemIdentity() {
    }

    public static String base(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static String key(ItemStack stack) {
        String base = base(stack);
        boolean box = !stack.isEmpty() && ItemIdentity.isBox(base);
        DataComponentPatch patch = stack.getComponentsPatch();
        if (box && stack.get(DataComponents.CONTAINER) == null) {
            ItemStack normalized = stack.copy();
            normalized.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
            patch = normalized.getComponentsPatch();
        }
        if (patch.isEmpty()) return base;
        return VARIANT_KEYS.computeIfAbsent(new Variant(base, patch), variant -> {
            String category = box ? boxCategory(stack) : "";
            return variant.base() + "#s" + SESSION + "-" + Long.toString(NEXT_ID.incrementAndGet(), 36)
                    + (category.isEmpty() ? "" : "@" + category);
        });
    }

    private record Variant(String base, DataComponentPatch patch) {
    }

    public static boolean isBox(ItemStack stack) {
        return !stack.isEmpty() && ItemIdentity.isBox(base(stack));
    }

    public static String boxCategory(ItemStack stack) {
        if (!isBox(stack)) {
            return "";
        }
        Set<String> categories = contents(stack).nonEmptyItemCopyStream()
                .map(MinecraftItemIdentity::base).collect(Collectors.toSet());
        return ItemIdentity.boxContentsCategory(categories);
    }

    public static ItemContainerContents contents(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
    }

    public static Set<String> unstackableItems(List<ItemStack> stacks) {
        return stacks.stream().flatMap(stack -> isBox(stack)
                        ? contents(stack).nonEmptyItemCopyStream()
                        : java.util.stream.Stream.of(stack))
                .filter(stack -> !stack.isEmpty() && !isBox(stack) && stack.getMaxStackSize() == 1)
                .map(MinecraftItemIdentity::key)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Recovery compares decoded components and quantities, independent of JSON key order. */
    public static boolean sameRecoveredBox(ItemStack expected, ItemStack actual) {
        if (!isBox(expected) || !isBox(actual)) return false;
        ItemStack expectedShell = expected.copyWithCount(1);
        ItemStack actualShell = actual.copyWithCount(1);
        expectedShell.remove(DataComponents.CONTAINER);
        actualShell.remove(DataComponents.CONTAINER);
        if (!ItemStack.isSameItemSameComponents(expectedShell, actualShell)) return false;
        List<ItemStack> remaining = new ArrayList<>(contents(actual).nonEmptyItemCopyStream()
                .toList());
        for (ItemStack wanted : contents(expected).nonEmptyItemCopyStream().toList()) {
            int count = wanted.getCount();
            for (ItemStack candidate : remaining) {
                if (!ItemStack.isSameItemSameComponents(wanted, candidate)) continue;
                int matched = Math.min(count, candidate.getCount());
                candidate.shrink(matched);
                count -= matched;
                if (count == 0) break;
            }
            if (count != 0) return false;
        }
        return remaining.stream().allMatch(ItemStack::isEmpty);
    }

    public static boolean full(ItemStack stack) {
        var items = contents(stack).nonEmptyItemCopyStream().toList();
        return items.size() == 27 && items.stream().allMatch(item ->
                item.getCount() == item.getMaxStackSize());
    }
}
