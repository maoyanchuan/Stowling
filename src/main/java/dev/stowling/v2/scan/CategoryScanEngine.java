package dev.stowling.v2.scan;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.category.CategoryMarker;
import dev.stowling.v2.category.CategoryRuleRegistry;
import dev.stowling.v2.category.CategoryZoneClassifier;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryZoneOptions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure geometry inference and atomic slice validation, independent of Minecraft and HTTP. */
public final class CategoryScanEngine {
    private static final CategoryRuleRegistry RULES = CategoryRuleRegistry.standard();

    private record Candidate(String axis, int step, int count, int mother, int hits, int score) {
    }

    private CategoryScanEngine() {
    }

    public static CategoryScanResult preview(CategoryScanSnapshot world, CategoryZoneOptions options) {
        List<CategoryScanBlock> containers = world.blocks().stream()
                .filter(CategoryScanBlock::container).toList();
        if (containers.isEmpty()) throw new IllegalArgumentException("总选区内没有储存容器");
        List<Candidate> candidates = new ArrayList<>();
        for (String axis : List.of("X", "Z")) {
            int length = axis.equals("X") ? world.area().maxX() - world.area().minX() + 1
                    : world.area().maxZ() - world.area().minZ() + 1;
            for (int step = 1; step <= length / 2; step++) {
                int count = (length + step - 1) / step;
                Map<List<String>, List<Integer>> layouts = new HashMap<>();
                Map<Integer, List<CategoryScanBlock>> partitions = new HashMap<>();
                var crossing = new java.util.HashSet<Integer>();
                for (var block : containers) {
                    int coordinate = axis.equals("X") ? block.position().x() : block.position().z();
                    int minimum = axis.equals("X") ? world.area().minX() : world.area().minZ();
                    int slot = (coordinate - minimum) / step;
                    var slab = CategoryScanDefinition.slab(world.area(), axis, step, slot);
                    if (!slab.contains(block.bounds())) {
                        crossing.add(slot);
                        if (block.partner() != null) {
                            int partner = axis.equals("X") ? block.partner().x() : block.partner().z();
                            crossing.add((partner - minimum) / step);
                        }
                    }
                    partitions.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(block);
                }
                for (int index = 0; index < count; index++) {
                    var inside = partitions.getOrDefault(index, List.of());
                    // A chest crossing a boundary is never compared as an independent half.
                    if (inside.isEmpty() || crossing.contains(index)) continue;
                    List<String> layout = signature(inside, axis, -index * step);
                    layouts.computeIfAbsent(layout, ignored -> new ArrayList<>()).add(index);
                }
                var ranked = layouts.entrySet().stream()
                        .sorted(Comparator.<Map.Entry<List<String>, List<Integer>>>comparingInt(
                                entry -> entry.getValue().size()).reversed()).toList();
                if (ranked.isEmpty() || ranked.getFirst().getValue().size() < 2) continue;
                int hits = ranked.getFirst().getValue().size();
                if (ranked.size() > 1 && ranked.get(1).getValue().size() == hits) continue;
                candidates.add(new Candidate(axis, step, count,
                        ranked.getFirst().getValue().getFirst(), hits,
                        hits * ranked.getFirst().getKey().size()));
                if (hits == count && hits * ranked.getFirst().getKey().size() == containers.size()) {
                    break;
                }
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::score).reversed()
                .thenComparing(Comparator.comparingInt(Candidate::hits).reversed())
                .thenComparingInt(Candidate::step));
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("没有唯一的重复储存布局；请核对选区或使用手动配置");
        }
        Candidate best = candidates.getFirst();
        if (best.count() > 256) {
            throw new IllegalArgumentException("重复储存布局超过 256 片，请缩小总选区后重新扫描");
        }
        if (candidates.stream().anyMatch(candidate -> !candidate.axis().equals(best.axis())
                && candidate.score() == best.score() && candidate.hits() == best.hits())) {
            throw new IllegalArgumentException("X、Z 两个方向的重复布局并列，请缩小选区消除歧义");
        }
        AreaBounds mother = CategoryScanDefinition.slab(world.area(), best.axis(), best.step(),
                best.mother());
        List<CategoryScanBlock> blocks = world.blocks().stream()
                .filter(block -> mother.contains(block.bounds())).toList();
        List<CategoryBindingDefinition> templates = new ArrayList<>();
        for (var container : blocks.stream().filter(CategoryScanBlock::container).toList()) {
            AreaBounds markers = candidateMarkers(world, container, best, mother);
            templates.add(new CategoryBindingDefinition(UUID.randomUUID(), markers,
                    container.bounds(), "DIRECT_BLOCK_ID", List.of(), List.of(), null, null,
                    false, "扫描组 " + (templates.size() + 1), false, null));
        }
        var scan = new CategoryScanDefinition(world.area(), world.dimension(), best.axis(),
                best.step(), best.mother(), best.count(), templates, blocks, List.of());
        return evaluate(world, scan, options);
    }

    private static AreaBounds candidateMarkers(CategoryScanSnapshot world,
            CategoryScanBlock container, Candidate candidate, AreaBounds mother) {
        var box = container.bounds();
        boolean lateralX = Set.of("north", "south").contains(container.facing());
        List<AreaBounds> positions = new ArrayList<>();
        for (int side : List.of(-1, 1)) {
            int x = lateralX ? (side < 0 ? box.minX() - 1 : box.maxX() + 1) : box.minX();
            int z = lateralX ? box.minZ() : side < 0 ? box.minZ() - 1 : box.maxZ() + 1;
            var area = new AreaBounds(x, box.minY(), z, x, box.maxY() + 1, z);
            if (mother.contains(area)) positions.add(area);
        }
        // End-facing layouts can instead display on the face in the slice plane.
        if (positions.isEmpty()) {
            for (int side : List.of(-1, 1)) {
                int x = candidate.axis().equals("X") ? box.minX()
                        : side < 0 ? box.minX() - 1 : box.maxX() + 1;
                int z = candidate.axis().equals("Z") ? box.minZ()
                        : side < 0 ? box.minZ() - 1 : box.maxZ() + 1;
                var area = new AreaBounds(x, box.minY(), z, x, box.maxY() + 1, z);
                if (mother.contains(area)) positions.add(area);
            }
        }
        if (positions.isEmpty()) throw new IllegalArgumentException(
                "储存容器 " + container.position() + " 周围没有完整的候选展示位，请扩大总选区");
        return positions.stream().max(Comparator.comparingLong(area -> {
            long score = 0;
            for (int i = 0; i < candidate.count(); i++) {
                var shifted = shift(area, candidate.axis(), (i - candidate.mother()) * candidate.step());
                if (world.markers().stream().anyMatch(marker -> shifted.contains(marker.position()))) {
                    score++;
                }
            }
            return score;
        })).orElseThrow();
    }

    public static CategoryScanResult evaluate(CategoryScanSnapshot world,
            CategoryScanDefinition requested, CategoryZoneOptions global) {
        var templates = requested.templates().stream().map(template -> {
            var options = template.optionsEnabled() ? template.categoryOptions() : global;
            if (options == null) return template;
            options.validateActiveRules();
            return new CategoryBindingDefinition(template.id(), template.markerArea(),
                    template.storageArea(), options.ruleId(), options.ignoredBlockIds(),
                    template.containerOverrides(), template.standingArea(),
                    options.storagePolicy(template.storagePolicy()), false, template.name(),
                    template.optionsEnabled(), template.categoryOptions(), template.standingPlan());
        }).toList();
        return evaluateCompiled(world, requested.withTemplates(templates), global);
    }

    private static CategoryScanResult evaluateCompiled(CategoryScanSnapshot world,
            CategoryScanDefinition scan, CategoryZoneOptions global) {
        if (!sameArea(world.area(), scan.area()) || !world.dimension().equals(scan.dimension())) {
            throw new IllegalArgumentException("总选区或游戏维度已改变，请重新扫描");
        }
        var expected = scan.blocks().stream().filter(CategoryScanBlock::container).toList();
        if (expected.isEmpty()) throw new IllegalArgumentException("母版中必须保留储存容器");
        for (var template : scan.templates()) {
            var effective = template.optionsEnabled() ? template.categoryOptions() : global;
            if (!"DIRECT_BLOCK_ID".equals(template.ruleId())
                    || effective != null && !"marker".equals(effective.recognitionMode())) {
                throw new IllegalArgumentException("扫描生成仅支持标识物识别，不开箱读取库存");
            }
        }
        for (var block : expected) {
            if (scan.templates().stream().filter(template ->
                    template.storageArea().contains(block.bounds())).count() != 1) {
                throw new IllegalArgumentException("母版容器 " + block.position()
                        + " 必须且只能属于一个完整储存范围");
            }
        }
        Map<Integer, List<CategoryZoneClassifier.Scan>> planned = new LinkedHashMap<>();
        Map<Integer, String> failures = new HashMap<>();
        Map<UUID, UUID> owners = new HashMap<>();
        for (int index = 0; index < scan.sliceCount(); index++) {
            int delta = scan.delta(index);
            var slab = CategoryScanDefinition.slab(scan.area(), scan.axis(), scan.step(), index);
            var actual = world.blocks().stream().filter(CategoryScanBlock::container)
                    .filter(block -> slab.contains(block.position())
                            || block.partner() != null && slab.contains(block.partner())).toList();
            var shifted = expected.stream().map(block -> block.shift(scan.axis(), delta))
                    .toList();
            String difference = difference(shifted, actual);
            if (difference != null) { failures.put(index, difference); continue; }
            List<CategoryZoneClassifier.Scan> groupScans = new ArrayList<>();
            for (var template : scan.templates()) {
                var markers = shift(template.markerArea(), scan.axis(), scan.delta(index));
                var storage = shift(template.storageArea(), scan.axis(), scan.delta(index));
                var targets = shifted.stream().filter(block -> storage.contains(block.bounds())).toList();
                if (targets.isEmpty()) {
                    failures.put(index, "组「" + template.name() + "」的储存范围没有容器：" + storage);
                    break;
                }
                var options = template.optionsEnabled() ? template.categoryOptions() : global;
                var effectiveMarkers = world.markers().stream()
                        .filter(marker -> markers.contains(marker.position())).toList();
                for (var target : targets) {
                    var binding = runtimeBinding(template, target, scan, index);
                    owners.put(binding.id(), template.id());
                    groupScans.add(new CategoryZoneClassifier.Scan(binding, options,
                            template.optionsEnabled() ? template.id() : null, effectiveMarkers,
                            List.of(new ContainerTarget(target.position(), target.blockId(),
                                    binding.standingArea(), binding.storagePolicy(), target.partner()))));
                }
            }
            if (!failures.containsKey(index)) planned.put(index, groupScans);
        }
        var prepared = CategoryZoneClassifier.prepare(planned.values().stream()
                .flatMap(List::stream).toList(), RULES, null);
        Map<UUID, CategoryZoneClassifier.Scan> byId = new HashMap<>();
        prepared.forEach(value -> byId.put(value.binding().id(), value));
        List<CategoryBindingDefinition> bindings = new ArrayList<>();
        List<CategoryScanSlice> slices = new ArrayList<>();
        for (int index = 0; index < scan.sliceCount(); index++) {
            String reason = failures.get(index);
            if (reason == null) {
                var checks = planned.get(index).stream().map(value -> byId.get(value.binding().id()))
                        .toList();
                reason = markerFailure(checks, owners);
                if (reason == null) {
                    // Append only after every group passed. No partial slice survives an error.
                    bindings.addAll(checks.stream().map(CategoryZoneClassifier.Scan::binding).toList());
                }
            }
            var slab = CategoryScanDefinition.slab(scan.area(), scan.axis(), scan.step(), index);
            slices.add(new CategoryScanSlice(index, new BlockPosition(
                    slab.minX(), slab.minY(), slab.minZ()), reason));
        }
        return new CategoryScanResult(new CategoryScanDefinition(scan.area(), scan.dimension(),
                scan.axis(), scan.step(), scan.templateIndex(), scan.sliceCount(), scan.templates(),
                scan.blocks(), slices, scan.canvasSize()), bindings);
    }

    private static String markerFailure(List<CategoryZoneClassifier.Scan> scans,
            Map<UUID, UUID> owners) {
        for (var scan : scans) {
            for (var marker : scan.markers()) {
                if (scans.stream().anyMatch(other ->
                        !owners.get(other.binding().id()).equals(owners.get(scan.binding().id()))
                                && other.markers().contains(marker))) {
                    return "展示物 " + marker.position() + " 同时落入多个组，无法可靠归属";
                }
            }
        }
        var result = CategoryZoneClassifier.resolvePrepared(scans, RULES, null, false);
        if (!result.succeeded()) return result.message();
        for (var scan : scans) {
            var target = scan.containers().getFirst();
            if (result.categories().values().stream().noneMatch(values -> values.contains(target))) {
                return "组「" + scan.binding().name() + "」的候选展示位 "
                        + scan.binding().markerArea() + " 没有有效标记";
            }
        }
        return null;
    }

    private static String difference(List<CategoryScanBlock> expected, List<CategoryScanBlock> actual) {
        for (var block : expected) {
            if (actual.stream().noneMatch(value -> shapeKey(value).equals(shapeKey(block)))) {
                return "容器 " + block.position() + " 预期 " + (block.partner() == null ? "单格容器" : "双箱")
                        + "（" + block.blockId() + "，朝向 " + block.facing()
                        + "），现场缺失、箱体组合或位置不同";
            }
        }
        if (actual.size() != expected.size()) {
            var extra = actual.stream().filter(block -> expected.stream().noneMatch(value ->
                    shapeKey(value).equals(shapeKey(block)))).findFirst().orElse(actual.getFirst());
            return "存在母版之外的储存容器：" + extra.position();
        }
        return null;
    }

    /** Reconstruct saved effective bindings without filling skipped slices or consulting the world. */
    public static List<CategoryBindingDefinition> savedBindings(CategoryScanDefinition scan) {
        if (scan.slices().size() != scan.sliceCount()) {
            throw new IllegalArgumentException("扫描结果尚未核对");
        }
        List<CategoryBindingDefinition> result = new ArrayList<>();
        var containers = scan.blocks().stream().filter(CategoryScanBlock::container).toList();
        if (containers.isEmpty()) throw new IllegalArgumentException("扫描母版没有储存容器");
        for (var block : containers) {
            if (scan.templates().stream().filter(template ->
                    template.storageArea().contains(block.bounds())).count() != 1) {
                throw new IllegalArgumentException("扫描母版的容器归属不完整或重叠");
            }
        }
        for (var slice : scan.slices()) {
            if (!slice.reason().isEmpty()) continue;
            for (var template : scan.templates()) {
                for (var block : scan.blocks()) {
                    if (block.container() && template.storageArea().contains(block.bounds())) {
                        var binding = runtimeBinding(template, block.shift(scan.axis(),
                                scan.delta(slice.index())), scan, slice.index());
                        if (!scan.area().contains(binding.markerArea())
                                || !scan.area().contains(binding.storageArea())) {
                            throw new IllegalArgumentException("有效扫描片越出总选区");
                        }
                        result.add(binding);
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    public static CategoryBindingDefinition templateFor(CategoryScanDefinition scan, int index) {
        int count = (int) scan.blocks().stream().filter(CategoryScanBlock::container).count();
        int slot = index % count;
        for (var template : scan.templates()) {
            int size = (int) scan.blocks().stream().filter(CategoryScanBlock::container)
                    .filter(block -> template.storageArea().contains(block.bounds())).count();
            if (slot < size) return template;
            slot -= size;
        }
        throw new IllegalArgumentException("扫描绑定与母版不一致");
    }

    public static CategoryBindingDefinition templateFor(CategoryScanDefinition scan, UUID bindingId) {
        var saved = savedBindings(scan);
        for (int i = 0; i < saved.size(); i++) {
            if (saved.get(i).id().equals(bindingId)) return templateFor(scan, i);
        }
        throw new IllegalArgumentException("扫描绑定不属于已保存的有效片");
    }

    private static CategoryBindingDefinition runtimeBinding(CategoryBindingDefinition template,
            CategoryScanBlock target, CategoryScanDefinition scan, int index) {
        int delta = scan.delta(index);
        String key = template.id() + ":scan:" + index + ":" + target.position();
        var storage = target.bounds();
        var markers = shift(template.markerArea(), scan.axis(), delta);
        var plan = template.standingPlan().shift(scan.axis().equals("X") ? delta : 0,
                scan.axis().equals("Z") ? delta : 0);
        if (plan.enabled()) {
            for (var point : plan.points()) {
                if (point.usesSubregions() && overlaps(point.storageArea(), storage)
                        && !point.storageArea().contains(storage)) {
                    throw new IllegalArgumentException("站位子区切开了完整容器：" + target.position());
                }
            }
            var points = plan.points().stream()
                    .filter(point -> !point.usesSubregions() || point.storageArea().contains(storage))
                    .map(point -> point.usesSubregions()
                            ? new dev.stowling.v2.model.CategoryStandingPoint(point.position(),
                                    point.markerArea(), storage, point.expanded(), true,
                                    point.standingId()) : point).toList();
            plan = new dev.stowling.v2.model.CategoryStandingPlan(!points.isEmpty(), points);
        }
        // A scanned group may cover several containers. Each effective binding has one physical
        // container, so the existing column matcher maps all of this group's candidates together.
        return new CategoryBindingDefinition(
                UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
                markers, storage, template.ruleId(), template.ignoredMarkerBlockIds(),
                template.containerOverrides().stream().map(override -> {
                    var position = override.storagePosition();
                    var shifted = new BlockPosition(
                            Math.addExact(position.x(), scan.axis().equals("X") ? delta : 0),
                            position.y(),
                            Math.addExact(position.z(), scan.axis().equals("Z") ? delta : 0));
                    String overrideKey = override.id() + ":scan:" + index;
                    return new dev.stowling.v2.model.CategoryContainerOverrideDefinition(
                            UUID.nameUUIDFromBytes(overrideKey.getBytes(StandardCharsets.UTF_8)),
                            shifted, override.mode(), override.itemIds());
                })
                        .filter(override -> storage.contains(override.storagePosition())).toList(),
                template.standingArea() == null ? null
                        : shift(template.standingArea(), scan.axis(), delta),
                template.storagePolicy().shift(scan.axis().equals("X") ? delta : 0,
                        scan.axis().equals("Z") ? delta : 0),
                false, template.name(), template.optionsEnabled(), template.categoryOptions(),
                plan);
    }

    private static List<String> signature(List<CategoryScanBlock> blocks, String axis, int delta) {
        return blocks.stream().map(block -> shapeKey(block.shift(axis, delta))).sorted().toList();
    }

    private static String shapeKey(CategoryScanBlock block) {
        return block.position() + ":" + block.partner() + ":" + block.blockId() + ":" + block.facing();
    }

    private static AreaBounds shift(AreaBounds bounds, String axis, int delta) {
        return bounds.shift(axis.equals("X") ? delta : 0, 0, axis.equals("Z") ? delta : 0);
    }

    private static boolean overlaps(AreaBounds a, AreaBounds b) {
        return a.minX() <= b.maxX() && a.maxX() >= b.minX()
                && a.minY() <= b.maxY() && a.maxY() >= b.minY()
                && a.minZ() <= b.maxZ() && a.maxZ() >= b.minZ();
    }

    public static boolean sameArea(AreaBounds first, AreaBounds second) {
        return first != null && second != null && first.contains(second) && second.contains(first);
    }
}
