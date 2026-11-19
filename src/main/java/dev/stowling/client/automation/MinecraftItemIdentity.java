package dev.stowling.client.automation;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.stowling.automation.ItemIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

public final class MinecraftItemIdentity {
    private MinecraftItemIdentity() {
    }

    public static String base(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static String key(ItemStack stack) {
        String base = base(stack);
        if (isBox(stack)) {
            stack = stack.copyWithCount(1);
            stack.set(DataComponents.CONTAINER, contents(stack));
        }
        if (stack.getComponentsPatch().isEmpty()) {
            return base;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            throw new IllegalStateException("物品数据读取需要已加载世界");
        }
        JsonElement data = ItemStack.CODEC.encodeStart(
                RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()),
                stack.copyWithCount(1)
        ).getOrThrow();
        String category = boxCategory(stack);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    data.toString().getBytes(StandardCharsets.UTF_8));
            return base + "#" + HexFormat.of().formatHex(digest)
                    + (category.isEmpty() ? "" : "@" + category);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
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
