package dev.stowling.client.automation;

import dev.stowling.automation.CategoryZonePort;
import dev.stowling.automation.CategoryZoneResult;
import dev.stowling.automation.ContainerRegionPort;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.category.CategoryMarker;
import dev.stowling.v2.category.CategoryMarkerSource;
import dev.stowling.v2.category.CategoryRuleRegistry;
import dev.stowling.v2.category.CategoryRuleResult;
import dev.stowling.v2.category.CategoryZoneClassifier;
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
                null, false
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
                Set.copyOf(markerSources), false
        );
    }

    @Override
    public CategoryZoneResult resolve(CategoryZoneDefinition zone, Set<String> containerTypes,
            int maxVolume, Map<BlockPosition, Set<String>> markerContainerContents,
            Set<CategoryMarkerSource> markerSources, boolean classificationTask) {
        if (markerSources != null && markerSources.isEmpty()) {
            return CategoryZoneResult.failure("未选择分类标记来源");
        }
        return resolveZone(zone, containerTypes, maxVolume, markerContainerContents,
                markerSources, classificationTask);
    }

    private CategoryZoneResult resolveZone(
            CategoryZoneDefinition zone,
            Set<String> containerTypes,
            int maxVolume,
            Map<BlockPosition, Set<String>> markerContainerContents,
            Set<CategoryMarkerSource> markerSources,
            boolean classificationTask
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
        if (zone.scan() != null && !zone.scan().dimension().equals(
                level.dimension().identifier().toString())) {
            return CategoryZoneResult.failure("当前维度与扫描分类区不同，请返回扫描时的维度");
        }
        try {
            return resolveBindings(
                    level,
                    zone,
                    containerTypes,
                    maxVolume,
                    markerContainerContents,
                    markerSources, classificationTask
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
            Set<CategoryMarkerSource> markerSources,
            boolean classificationTask
    ) {
        List<CategoryZoneClassifier.Scan> scans = new ArrayList<>();
        List<CategoryMarker> scannedMarkers = zone.scan() == null ? null
                : scanMarkers(level, zone.scan().area());
        Map<BlockPosition, AreaBounds> assignedStances = new java.util.LinkedHashMap<>();
        for (var segment : dev.stowling.v2.model.CategoryStandingSubregions.segments(
                zone, classificationTask)) {
            CategoryBindingDefinition binding = segment.binding();
            var options = segment.options();
            Set<BlockPosition> excluded = options == null ? Set.of()
                    : Set.copyOf(options.excludedMarkerPositions());
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
                        sameMarkerAndStorageArea(binding), excluded);
            } else if (scannedMarkers != null && DirectBlockIdCategoryRule.ID.equals(binding.ruleId())) {
                var sources = markerSourcesFor(binding.ruleId(), markerSources);
                markers = scannedMarkers.stream()
                        .filter(marker -> binding.markerArea().contains(marker.position()))
                        .filter(marker -> sources.contains(marker.source()))
                        .filter(marker -> !excluded.contains(marker.position())).toList();
            } else {
                markers = markers(level, binding.markerArea(),
                        Set.copyOf(binding.ignoredMarkerBlockIds()), markerContainerContents,
                        markerSourcesFor(binding.ruleId(), markerSources), excluded);
            }
            if (classificationTask) {
                for (var container : storageScan.containers()) {
                    for (var entrance : container.entrances()) {
                        if (assignedStances.containsKey(entrance) && !Objects.equals(
                                assignedStances.get(entrance), binding.standingArea())) {
                            return CategoryZoneResult.failure("双箱或容器跨越不同站位的子储存区："
                                    + entrance + "，请调整子区边界");
                        }
                        assignedStances.put(entrance, binding.standingArea());
                    }
                }
            }
            scans.add(new CategoryZoneClassifier.Scan(binding, options,
                    segment.scopeId(), markers,
                    storageScan.containers().stream().map(target ->
                            target.withStandingArea(binding.standingArea(), binding.storagePolicy()))
                            .toList()));
        }
        CategoryRuleResult resolved = CategoryZoneClassifier.resolve(scans, rules, markerSources);
        if (!resolved.succeeded()) {
            return CategoryZoneResult.failure("分类区「" + zone.name() + "」" + resolved.message());
        }
        var categories = resolved.categories();
        var layout = resolved.layout();
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

    static List<CategoryMarker> markers(
            ClientLevel level,
            AreaBounds area,
            Set<String> ignoredBlockIds,
            Map<BlockPosition, Set<String>> markerContainerContents,
            Set<CategoryMarkerSource> markerSources,
            Set<BlockPosition> excluded
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
                    if (excluded.contains(blockPosition(position)) || !level.isLoaded(position)) {
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
            appendItemFrameMarkers(level, area, result, false, excluded);
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

    /** Only scanned configurations opt into relationship-based pot support filtering. */
    static List<CategoryMarker> scanMarkers(ClientLevel level, AreaBounds area) {
        List<CategoryMarker> result = new ArrayList<>(markers(level, area, Set.of(), Map.of(),
                SMART_MARKER_SOURCES, Set.of()));
        removePottedSupports(level, result);
        return List.copyOf(result);
    }

    static void removePottedSupports(LevelReader level, List<CategoryMarker> markers) {
        var supports = new java.util.HashSet<BlockPosition>();
        for (var marker : markers) {
            if (marker.source() != CategoryMarkerSource.FLOWER_POT) continue;
            var p = marker.position();
            var pot = new BlockPos(p.x(), p.y(), p.z());
            var support = pot.below();
            if (level.getBlockState(pot).canSurvive(level, pot)) {
                supports.add(blockPosition(support));
            }
        }
        // markers() returns an immutable list; callers that need this filtering use a mutable copy.
        markers.removeIf(marker -> marker.source() == CategoryMarkerSource.PLACED_ITEM
                && supports.contains(marker.position()));
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
            if (ContainerContentsCategoryRule.CONTAINER_TYPES.contains(blockId)) {
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
            boolean attachedOnly,
            Set<BlockPosition> excluded
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
            BlockPos support = itemFrameSupport(framePos, frame.getDirection());
            BlockPos position = attachedOnly || !contains(area, framePos) ? support : framePos;
            if (!contains(area, position) || excluded.contains(blockPosition(position))) {
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

    static BlockPos itemFrameSupport(BlockPos position, net.minecraft.core.Direction facing) {
        return position.relative(facing.getOpposite());
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
