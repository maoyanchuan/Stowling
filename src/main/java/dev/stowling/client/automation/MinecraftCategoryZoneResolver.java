package dev.stowling.client.automation;

import dev.stowling.automation.CategoryZonePort;
import dev.stowling.automation.CategoryZoneResult;
import dev.stowling.automation.ClassificationLayoutEntry;
import dev.stowling.automation.ContainerRegionPort;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.category.CategoryMarker;
import dev.stowling.v2.category.CategoryMarkerSource;
import dev.stowling.v2.category.CategoryRule;
import dev.stowling.v2.category.CategoryRuleRegistry;
import dev.stowling.v2.category.CategoryRuleResult;
import dev.stowling.v2.category.ContainerContentsCategoryRule;
import dev.stowling.v2.category.DirectBlockIdCategoryRule;
import dev.stowling.v2.category.PottedBlockItems;
import dev.stowling.v2.category.UnstackableCategoryRule;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.RegionDefinition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

public final class MinecraftCategoryZoneResolver
        implements CategoryZonePort {
    private static final Set<String> MARKER_CONTAINER_TYPES = Set.of(
            "minecraft:chest",
            "minecraft:trapped_chest",
            "minecraft:barrel"
    );
    private static final Set<CategoryMarkerSource> ALL_MARKER_SOURCES =
            Set.copyOf(EnumSet.allOf(CategoryMarkerSource.class));
    private static final Set<CategoryMarkerSource> SMART_MARKER_SOURCES =
            Set.copyOf(CategoryMarkerSource.SMART_SOURCES);

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
        return resolve(zone, containerTypes, maxVolume, Map.of());
    }

    @Override
    public CategoryZoneResult resolve(
            CategoryZoneDefinition zone,
            Set<String> containerTypes,
            int maxVolume,
            Map<BlockPosition, Set<String>> markerContainerContents
    ) {
        return resolveZone(
                zone,
                containerTypes,
                maxVolume,
                markerContainerContents,
                null
        );
    }

    @Override
    public CategoryZoneResult resolve(
            CategoryZoneDefinition zone,
            Set<String> containerTypes,
            int maxVolume,
            Map<BlockPosition, Set<String>> markerContainerContents,
            Set<CategoryMarkerSource> markerSources
    ) {
        Objects.requireNonNull(markerSources, "markerSources");
        if (markerSources.isEmpty()) {
            return CategoryZoneResult.failure("未选择分类标记来源");
        }
        return resolveZone(
                zone,
                containerTypes,
                maxVolume,
                markerContainerContents,
                Set.copyOf(markerSources)
        );
    }

    private CategoryZoneResult resolveZone(
            CategoryZoneDefinition zone,
            Set<String> containerTypes,
            int maxVolume,
            Map<BlockPosition, Set<String>> markerContainerContents,
            Set<CategoryMarkerSource> markerSources
    ) {
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(containerTypes, "containerTypes");
        Objects.requireNonNull(
                markerContainerContents,
                "markerContainerContents"
        );
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
                    maxVolume,
                    markerContainerContents,
                    markerSources
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
            int maxVolume,
            Map<BlockPosition, Set<String>> markerContainerContents,
            Set<CategoryMarkerSource> markerSources
    ) {
        Map<String, List<ContainerTarget>> categories =
                new LinkedHashMap<>();
        List<ClassificationLayoutEntry> layout = new ArrayList<>();
        for (CategoryBindingDefinition binding : zone.bindings()) {
            boolean unstackable = UnstackableCategoryRule.ID.equals(binding.ruleId());
            if (volume(binding.markerArea()) > maxVolume) {
                return CategoryZoneResult.failure(
                        "分类区 " + zone.name() + " 的识别区体积过大"
                );
            }
            ContainerScanResult storageScan = containerRegions.scan(
                    asRegion(binding),
                    containerTypes,
                    maxVolume
            );
            if (!storageScan.succeeded()) {
                return CategoryZoneResult.failure(storageScan.message());
            }
            List<CategoryMarker> markers = new ArrayList<>();
            if (unstackable) {
                appendItemFrameMarkers(level, binding.markerArea(), markers,
                        sameMarkerAndStorageArea(binding));
            } else {
                markers = markers(level, binding.markerArea(),
                        Set.copyOf(binding.ignoredMarkerBlockIds()), markerContainerContents,
                        markerSourcesFor(binding.ruleId(), markerSources));
            }
            CategoryRuleResult resolved = resolveBinding(
                    binding,
                    markers,
                    storageScan.containers().stream().map(target ->
                            target.withStandingArea(binding.standingArea(), binding.storagePolicy()))
                            .toList(),
                    markerSources
            );
            if (!resolved.succeeded()) {
                return CategoryZoneResult.failure("分类区「" + zone.name() + "」识别起点 ("
                        + binding.markerArea().firstX() + ", " + binding.markerArea().firstY()
                        + ", " + binding.markerArea().firstZ() + ")：" + resolved.message());
            }
            mergeCategories(categories, resolved.categories());
            layout.addAll(resolved.layout());
        }
        if (categories.isEmpty()) {
            if (zone.bindings().stream().allMatch(binding ->
                    UnstackableCategoryRule.ID.equals(binding.ruleId()))) {
                return CategoryZoneResult.success(categories, layout);
            }
            if (markerSources == null) {
                return CategoryZoneResult.failure(
                        "分类区 " + zone.name() + " 没有识别出分类"
                );
            }
            return CategoryZoneResult.failure(
                    "分类区 " + zone.name() + " 在选中的分类标记来源 "
                            + sourceNames(markerSources)
                            + " 中没有识别出分类"
            );
        }
        return CategoryZoneResult.success(categories, layout);
    }

    static Set<CategoryMarkerSource> markerSourcesFor(
            String ruleId,
            Set<CategoryMarkerSource> explicitSources
    ) {
        if (explicitSources != null) {
            return explicitSources;
        }
        return switch (ruleId) {
            case DirectBlockIdCategoryRule.ID -> SMART_MARKER_SOURCES;
            case ContainerContentsCategoryRule.ID ->
                    Set.of(CategoryMarkerSource.CONTAINER_CONTENTS);
            default -> ALL_MARKER_SOURCES;
        };
    }

    private CategoryRuleResult resolveBinding(
            CategoryBindingDefinition binding,
            List<CategoryMarker> markers,
            List<ContainerTarget> storageContainers,
            Set<CategoryMarkerSource> markerSources
    ) {
        if (UnstackableCategoryRule.ID.equals(binding.ruleId())) {
            if (sameMarkerAndStorageArea(binding)) {
                return rules.require(binding.ruleId()).resolve(markers, storageContainers);
            }
            return UnstackableCategoryRule.resolveMarkerArea(markers, storageContainers);
        }
        if (markerSources != null) {
            return DirectBlockIdCategoryRule.resolveExplicitSources(
                    markers,
                    storageContainers,
                    binding.containerOverrides(),
                    binding.wholeStorage()
            );
        }
        CategoryRule rule = rules.require(binding.ruleId());
        List<CategoryMarker> acceptedMarkers = markers.stream()
                .filter(rule::accepts)
                .toList();
        if (rule instanceof ContainerContentsCategoryRule contentsRule) {
            return contentsRule.resolve(acceptedMarkers, storageContainers,
                    binding.containerOverrides(), binding.wholeStorage());
        }
        return rule.resolve(
                acceptedMarkers,
                storageContainers,
                binding.containerOverrides()
        );
    }

    private static boolean sameMarkerAndStorageArea(CategoryBindingDefinition binding) {
        return binding.markerArea().contains(binding.storageArea())
                && binding.storageArea().contains(binding.markerArea());
    }

    private static String sourceNames(
            Set<CategoryMarkerSource> markerSources
    ) {
        List<String> names = new ArrayList<>();
        for (CategoryMarkerSource source : CategoryMarkerSource.values()) {
            if (markerSources.contains(source)) {
                names.add(source.name());
            }
        }
        return String.join(",", names);
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

    private static List<CategoryMarker> markers(
            ClientLevel level,
            AreaBounds area,
            Set<String> ignoredBlockIds,
            Map<BlockPosition, Set<String>> markerContainerContents,
            Set<CategoryMarkerSource> markerSources
    ) {
        List<CategoryMarker> result = new ArrayList<>();
        for (long x = area.minX(); x <= area.maxX(); x++) {
            for (long y = area.minY(); y <= area.maxY(); y++) {
                for (long z = area.minZ(); z <= area.maxZ(); z++) {
                    BlockPos position = new BlockPos(
                            (int) x,
                            (int) y,
                            (int) z
                    );
                    if (!level.isLoaded(position)) {
                        continue;
                    }
                    appendBlockMarkers(
                            level,
                            position,
                            ignoredBlockIds,
                            markerContainerContents,
                            markerSources,
                            result
                    );
                }
            }
        }
        if (markerSources.contains(CategoryMarkerSource.ITEM_FRAME)) {
            appendItemFrameMarkers(level, area, result, false);
        }
        result.sort(Comparator.<CategoryMarker>comparingLong(marker ->
                        Math.abs((long) marker.position().x() - area.firstX()))
                .thenComparingLong(marker ->
                        Math.abs((long) marker.position().y() - area.firstY()))
                .thenComparingLong(marker ->
                        Math.abs((long) marker.position().z() - area.firstZ()))
                .thenComparingInt(CategoryMarker::slotIndex));
        return List.copyOf(result);
    }

    static void appendBlockMarkers(
            LevelReader level,
            BlockPos position,
            Set<String> ignoredBlockIds,
            Map<BlockPosition, Set<String>> markerContainerContents,
            Set<CategoryMarkerSource> markerSources,
            List<CategoryMarker> result
    ) {
        BlockState state = level.getBlockState(position);
        if (state.isAir()) {
            return;
        }
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock())
                .toString();
        BlockPosition markerPosition = blockPosition(position);
        if (markerSources.contains(CategoryMarkerSource.CONTAINER_CONTENTS)) {
            Set<String> contents = markerContainerContents.get(markerPosition);
            if (contents != null) {
                appendContainerContents(markerPosition, contents, result);
                return;
            }
            if (MARKER_CONTAINER_TYPES.contains(blockId)) {
                return;
            }
        }
        String pottedItemId = pottedItemId(state, blockId);
        if (pottedItemId != null) {
            if (markerSources.contains(CategoryMarkerSource.FLOWER_POT)
                    || markerSources.contains(
                            CategoryMarkerSource.PLACED_ITEM
                    )) {
                result.add(new CategoryMarker(
                        markerPosition,
                        pottedItemId,
                        CategoryMarkerSource.FLOWER_POT,
                        -1
                ));
            }
            return;
        }
        if (level.getBlockEntity(position) instanceof ShelfBlockEntity shelf) {
            if (markerSources.contains(CategoryMarkerSource.SHELF)) {
                appendShelfItems(position, shelf, result);
            }
            return;
        }
        if (!markerSources.contains(CategoryMarkerSource.PLACED_ITEM)
                || ignoredBlockIds.contains(blockId)) {
            return;
        }
        appendItemMarker(
                result,
                position,
                state.getCloneItemStack(level, position, false),
                CategoryMarkerSource.PLACED_ITEM,
                -1
        );
    }

    private static String pottedItemId(
            BlockState state,
            String blockId
    ) {
        if (!blockId.substring(blockId.indexOf(':') + 1)
                .startsWith("potted_")) {
            return null;
        }
        if (state.getBlock() instanceof FlowerPotBlock flowerPot) {
            String itemId = BuiltInRegistries.ITEM.getKey(
                    flowerPot.getPotted().asItem()
            ).toString();
            if (!"minecraft:air".equals(itemId)) {
                return itemId;
            }
        }
        return PottedBlockItems.itemId(blockId).orElse(null);
    }

    private static void appendContainerContents(
            BlockPosition position,
            Set<String> itemIds,
            List<CategoryMarker> result
    ) {
        int slot = 0;
        for (String itemId : itemIds) {
            result.add(new CategoryMarker(
                    position,
                    itemId,
                    CategoryMarkerSource.CONTAINER_CONTENTS,
                    slot++
            ));
        }
    }

    private static void appendShelfItems(
            BlockPos position,
            ShelfBlockEntity shelf,
            List<CategoryMarker> result
    ) {
        List<ItemStack> items = shelf.getItems();
        for (int slot = 0; slot < items.size(); slot++) {
            appendItemMarker(
                    result,
                    position,
                    items.get(slot),
                    CategoryMarkerSource.SHELF,
                    slot
            );
        }
    }

    private static void appendItemFrameMarkers(
            ClientLevel level,
            AreaBounds area,
            List<CategoryMarker> result,
            boolean attachedOnly
    ) {
        AABB searchArea = new AABB(
                area.minX(),
                area.minY(),
                area.minZ(),
                area.maxX() + 1.0,
                area.maxY() + 1.0,
                area.maxZ() + 1.0
        );
        List<ItemFrame> frames = level.getEntities(
                EntityTypeTest.forClass(ItemFrame.class),
                searchArea.inflate(1.0),
                frame -> frame.isAlive()
                        && !frame.isRemoved()
                        && !frame.getItem().isEmpty()
        );
        for (ItemFrame frame : frames) {
            BlockPos framePos = frame.getPos();
            BlockPos support = framePos.relative(frame.getDirection().getOpposite());
            BlockPos position = attachedOnly || !contains(area, framePos) ? support : framePos;
            if (!contains(area, position)) {
                continue;
            }
            if (!attachedOnly) {
                result.removeIf(marker -> marker.source() == CategoryMarkerSource.PLACED_ITEM
                        && marker.position().equals(blockPosition(support)));
            }
            appendItemMarker(
                    result,
                    position,
                    frame.getItem(),
                    CategoryMarkerSource.ITEM_FRAME,
                    -1
            );
        }
    }

    private static void appendItemMarker(
            List<CategoryMarker> result,
            BlockPos position,
            ItemStack stack,
            CategoryMarkerSource source,
            int slotIndex
    ) {
        if (stack.isEmpty()) {
            return;
        }
        result.add(new CategoryMarker(
                blockPosition(position),
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                source,
                slotIndex
        ));
    }

    private static BlockPosition blockPosition(BlockPos position) {
        return new BlockPosition(
                position.getX(),
                position.getY(),
                position.getZ()
        );
    }

    private static boolean contains(AreaBounds area, BlockPos position) {
        return position.getX() >= area.minX()
                && position.getX() <= area.maxX()
                && position.getY() >= area.minY()
                && position.getY() <= area.maxY()
                && position.getZ() >= area.minZ()
                && position.getZ() <= area.maxZ();
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
