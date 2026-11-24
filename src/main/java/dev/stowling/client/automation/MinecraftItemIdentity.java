package dev.stowling.client.automation;

import com.google.common.collect.MapMaker;
import dev.stowling.automation.ItemIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

public final class MinecraftItemIdentity {
    // Keep a key while inventories or tasks reference it, then release its component snapshot.
    private static final ConcurrentMap<Variant, String> VARIANT_KEYS =
            new MapMaker().weakValues().makeMap();
    private static final ConcurrentMap<Variant, String> PERSISTENT_KEYS =
            new MapMaker().weakValues().makeMap();
    private static final String SESSION =
            Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36);
    private static final AtomicLong NEXT_ID = new AtomicLong();

    private MinecraftItemIdentity() {
    }

    public static String base(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    static String iconModel(ItemStack stack) {
        var model = stack.get(DataComponents.ITEM_MODEL);
        return model == null ? base(stack) : model.toString();
    }

    public static String key(ItemStack stack) {
        String base = base(stack);
        boolean box = !stack.isEmpty() && ItemIdentity.isBox(base);
        DataComponentPatch patch = normalizedPatch(stack, box);
        if (patch.isEmpty()) return base;
        return VARIANT_KEYS.computeIfAbsent(new Variant(base, patch), variant -> {
            String category = stack.has(DataComponents.CUSTOM_NAME)
                    ? ItemIdentity.NAMED_ITEM_CATEGORY : box ? boxCategory(stack) : "";
            return variant.base() + "#s" + SESSION + "-" + Long.toString(NEXT_ID.incrementAndGet(), 36)
                    + (category.isEmpty() ? "" : "@" + category);
        });
    }

    static String persistentKey(ItemStack stack, RegistryAccess registries) {
        String base = base(stack);
        boolean box = !stack.isEmpty() && ItemIdentity.isBox(base);
        DataComponentPatch patch = normalizedPatch(stack, box);
        if (patch.isEmpty()) return base;
        return PERSISTENT_KEYS.computeIfAbsent(new Variant(base, patch), variant -> {
            var nbt = DataComponentPatch.CODEC.encodeStart(
                    RegistryOps.create(NbtOps.INSTANCE, registries), variant.patch()).result()
                    .orElseThrow(() -> new IllegalStateException("无法生成背包锁定的物品身份"));
            // SNBT preserves tag types and sorts compound keys, including nested compounds.
            // Keep persisted backpack locks stable when classification rules change.
            String category = box ? ItemIdentity.boxContentsCategory(contents(stack)
                    .nonEmptyItemCopyStream().map(MinecraftItemIdentity::base)
                    .collect(Collectors.toSet())) : "";
            return base + "#p1-" + digest(nbt.toString())
                    + (category.isEmpty() ? "" : "@" + category);
        });
    }

    private static DataComponentPatch normalizedPatch(ItemStack stack, boolean box) {
        if (!box || stack.get(DataComponents.CONTAINER) != null) {
            return stack.getComponentsPatch();
        }
        ItemStack normalized = stack.copy();
        normalized.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        return normalized.getComponentsPatch();
    }

    private static String digest(String data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
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
        if (stack.has(DataComponents.CUSTOM_NAME)) return ItemIdentity.NAMED_ITEM_CATEGORY;
        Set<String> categories = contents(stack).nonEmptyItemCopyStream()
                .map(MinecraftItemIdentity::category).collect(Collectors.toSet());
        return ItemIdentity.boxContentsCategory(categories);
    }

    /** Custom names bypass ordinary classification and use the unclassifiable destination. */
    public static String category(ItemStack stack) {
        return stack.has(DataComponents.CUSTOM_NAME) ? ItemIdentity.NAMED_ITEM_CATEGORY : base(stack);
    }

    public static ItemContainerContents contents(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
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
