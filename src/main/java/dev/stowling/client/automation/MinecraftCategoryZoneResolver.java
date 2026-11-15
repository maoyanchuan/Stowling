package dev.stowling.client.automation;

import dev.stowling.automation.CategoryZonePort;
import dev.stowling.automation.CategoryZoneResult;
import dev.stowling.automation.ContainerRegionPort;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.v2.category.CategoryRule;
import dev.stowling.v2.category.CategoryRuleRegistry;
import dev.stowling.v2.category.CategoryRuleResult;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.RegionDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

public final class MinecraftCategoryZoneResolver
        implements CategoryZonePort {
    private final Minecraft minecraft;
    private final ClientActionScheduler scheduler;
    private final ContainerRegionPort containerRegions;
    private final CategoryRuleRegistry rules;

    public MinecraftCategoryZoneResolver(
            Minecraft minecraft,
            ClientActionScheduler scheduler,
            ContainerRegionPort containerRegions,
            CategoryRuleRegistry rules
    ) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.containerRegions = Objects.requireNonNull(
                containerRegions,
                "containerRegions"
        );
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    @Override
    public CategoryZoneResult resolve(
            CategoryZoneDefinition zone,
            Set<String> containerTypes,
            int maxVolume
    ) {
        Objects.requireNonNull(zone, "zone");
        if (!scheduler.isClientThread()) {
            return CategoryZoneResult.failure("分类区解析必须在客户端线程执行");
        }
        ClientLevel level = minecraft.level;
        if (level == null) {
            return CategoryZoneResult.failure("当前没有可识别的游戏世界");
        }
        try {
            return resolveBindings(
                    level,
                    zone,
                    containerTypes,
                    maxVolume
            );
        } catch (RuntimeException exception) {
            return CategoryZoneResult.failure(
                    "解析分类区时发生错误: " + exception.getMessage()
            );
        }
    }

    private CategoryZoneResult resolveBindings(
            ClientLevel level,
            CategoryZoneDefinition zone,
            Set<String> containerTypes,
            int maxVolume
    ) {
        Map<String, List<ContainerTarget>> categories =
                new LinkedHashMap<>();
        for (CategoryBindingDefinition binding : zone.bindings()) {
            long markerVolume = volume(binding.markerArea());
            if (markerVolume > maxVolume) {
                return CategoryZoneResult.failure(
                        "分类区 " + zone.name() + " 的识别区体积过大"
                );
            }
            List<String> markerBlockIds = markerBlockIds(
                    level,
                    binding.markerArea()
            );
            ContainerScanResult storageScan = containerRegions.scan(
                    asRegion(binding),
                    containerTypes,
                    maxVolume
            );
            if (!storageScan.succeeded()) {
                return CategoryZoneResult.failure(storageScan.message());
            }
            CategoryRule rule = rules.require(binding.ruleId());
            CategoryRuleResult resolved = rule.resolve(
                    markerBlockIds,
                    storageScan.containers()
            );
            if (!resolved.succeeded()) {
                return CategoryZoneResult.failure(resolved.message());
            }
            mergeCategories(categories, resolved.categories());
        }
        if (categories.isEmpty()) {
            return CategoryZoneResult.failure(
                    "分类区 " + zone.name() + " 没有识别出分类"
            );
        }
        return CategoryZoneResult.success(categories);
    }

    private static void mergeCategories(
            Map<String, List<ContainerTarget>> categories,
            Map<String, List<ContainerTarget>> incoming
    ) {
        for (Map.Entry<String, List<ContainerTarget>> entry
                : incoming.entrySet()) {
            String category = entry.getKey();
            List<ContainerTarget> merged = new ArrayList<>(
                    categories.getOrDefault(category, List.of())
            );
            for (ContainerTarget container : entry.getValue()) {
                if (!merged.contains(container)) {
                    merged.add(container);
                }
            }
            categories.put(category, List.copyOf(merged));
        }
    }

    private static List<String> markerBlockIds(
            ClientLevel level,
            AreaBounds area
    ) {
        Set<String> result = new LinkedHashSet<>();
        for (long x = area.minX(); x <= area.maxX(); x++) {
            for (long y = area.minY(); y <= area.maxY(); y++) {
                for (long z = area.minZ(); z <= area.maxZ(); z++) {
                    BlockPos position = new BlockPos(
                            (int) x,
                            (int) y,
                            (int) z
                    );
                    if (!level.isLoaded(position)
                            || level.getBlockState(position).isAir()) {
                        continue;
                    }
                    result.add(BuiltInRegistries.BLOCK.getKey(
                            level.getBlockState(position).getBlock()
                    ).toString());
                }
            }
        }
        return List.copyOf(result);
    }

    private static RegionDefinition asRegion(
            CategoryBindingDefinition binding
    ) {
        AreaBounds area = binding.storageArea();
        return new RegionDefinition(
                binding.id(),
                "category-storage",
                area.firstX(),
                area.firstY(),
                area.firstZ(),
                area.secondX(),
                area.secondY(),
                area.secondZ()
        );
    }

    private static long volume(AreaBounds area) {
        long width = (long) area.maxX() - area.minX() + 1;
        long height = (long) area.maxY() - area.minY() + 1;
        long depth = (long) area.maxZ() - area.minZ() + 1;
        return Math.multiplyExact(
                Math.multiplyExact(width, height),
                depth
        );
    }
}
