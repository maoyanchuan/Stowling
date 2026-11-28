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

    private record Candidate(String axis, int step, int count, int mother, int hits, int score,
            List<CategoryScanBlock> layout) {
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
                if (ranked.isEmpty()) continue;
                Map<String, Integer> occurrences = new HashMap<>();
                for (var entry : ranked) {
                    for (String key : entry.getKey()) {
                        occurrences.merge(key, entry.getValue().size(), Integer::sum);
                    }
                }
                var core = occurrences.entrySet().stream()
                        .filter(entry -> entry.getValue() > count / 2)
                        .map(Map.Entry::getKey).sorted().toList();
                int hits;
                int mother;
                if (!core.isEmpty()) {
                    var stableKeys = core;
                    var matching = ranked.stream()
                            .filter(entry -> entry.getKey().containsAll(stableKeys))
                            .toList();
                    hits = matching.stream().mapToInt(entry -> entry.getValue().size()).sum();
                    if (hits < 2) continue;
                    mother = matching.stream().min(Comparator.comparingInt(entry ->
                            entry.getKey().size())).orElseThrow().getValue().getFirst();
                } else {
                    hits = ranked.getFirst().getValue().size();
                    if (hits < 2) continue;
                    if (ranked.size() > 1 && ranked.get(1).getValue().size() == hits) continue;
                    core = ranked.getFirst().getKey();
                    mother = ranked.getFirst().getValue().getFirst();
                }
                var keys = Set.copyOf(core);
                int displacement = -mother * step;
                var layout = partitions.get(mother).stream().filter(block ->
                        keys.contains(shapeKey(block.shift(axis, displacement)))).toList();
                candidates.add(new Candidate(axis, step, count, mother, hits,
                        hits * layout.size(), layout));
                if (hits == count && hits * layout.size() == containers.size()) {
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
        var storage = best.layout().stream()
                .map(CategoryScanBlock::bounds).toList();
        var evidence = candidateEvidence(world.markers(), options);
        var choices = best.layout().stream().map(container ->
                candidateChoices(world, evidence, container, best, mother, storage)).toList();
        var assignment = CategoryScanAssignment.assign(choices);
        var alternatives = alternatives(choices, assignment);
        Map<UUID, List<BlockPosition>> anchors = new LinkedHashMap<>();
        List<CategoryBindingDefinition> templates = new ArrayList<>();
        for (int index = 0; index < best.layout().size(); index++) {
            var container = best.layout().get(index);
            var choice = assignment.choices().get(index);
            UUID id = UUID.randomUUID();
            var markers = choice.area();
            if (assignment.ambiguous().contains(index)) {
                for (var alternative : choices.get(index)) {
                    if (alternative.score() > 0) markers = enclosing(markers, alternative.area());
                }
            } else {
                var selected = alternatives.get(index);
                anchors.put(id, selected.stream().map(CategoryScanAssignment.Choice::anchor).toList());
                for (var alternative : selected) markers = enclosing(markers, alternative.area());
            }
            templates.add(new CategoryBindingDefinition(id, markers,
                    container.bounds(), "DIRECT_BLOCK_ID", List.of(), List.of(), null, null,
                    false, "扫描组 " + (templates.size() + 1), false, null));
        }
        var scan = new CategoryScanDefinition(world.area(), world.dimension(), best.axis(),
                best.step(), best.mother(), best.count(), templates, blocks, List.of(), null, anchors);
        return evaluate(world, scan, options);
    }

    private static List<CategoryMarker> candidateEvidence(List<CategoryMarker> markers,
            CategoryZoneOptions options) {
        var ignored = options == null ? Set.<String>of() : Set.copyOf(options.ignoredBlockIds());
        var excluded = options == null ? Set.<BlockPosition>of()
                : Set.copyOf(options.excludedMarkerPositions());
        return markers.stream().filter(marker -> !excluded.contains(marker.position()))
                .filter(marker -> marker.source()
                        != dev.stowling.v2.category.CategoryMarkerSource.PLACED_ITEM
                        || !ignored.contains(marker.itemId())).toList();
    }

    private static List<CategoryScanAssignment.Choice> candidateChoices(CategoryScanSnapshot world,
            List<CategoryMarker> evidence,
            CategoryScanBlock container, Candidate candidate, AreaBounds mother,
            List<AreaBounds> storage) {
        var box = container.bounds();
        boolean repeatedX = candidate.axis().equals("X");
        long minimumDepth = repeatedX ? box.minX() : box.minZ();
        long maximumDepth = repeatedX ? box.maxX() : box.maxZ();
        long minimumHorizontal = repeatedX ? box.minZ() : box.minX();
        long maximumHorizontal = repeatedX ? box.maxZ() : box.maxX();
        var positions = new java.util.LinkedHashSet<BlockPosition>();
        long y = box.minY();
        for (long depth = minimumDepth; depth <= maximumDepth; depth++) {
            for (long horizontal = minimumHorizontal - 1;
                    horizontal <= maximumHorizontal + 1; horizontal++) {
                for (long height = y - 1; height <= y + 1; height++) {
                    long x = repeatedX ? depth : horizontal;
                    long z = repeatedX ? horizontal : depth;
                    if (x < mother.minX() || x > mother.maxX() || z < mother.minZ()
                            || z > mother.maxZ() || height < mother.minY()
                            || height > mother.maxY()) continue;
                    var p = new BlockPosition((int) x, (int) height, (int) z);
                    if (storage.stream().noneMatch(bounds -> bounds.contains(p))) positions.add(p);
                }
            }
        }
        if (positions.isEmpty()) throw new IllegalArgumentException(
                "储存容器 " + container.position() + " 的相邻候选位置均越出当前单片或被储存箱体占用，"
                        + "请核对总选区与分片布局");
        List<CategoryScanAssignment.Choice> result = new ArrayList<>();
        for (var anchor : positions) {
            Set<Integer> covered = new java.util.HashSet<>();
            Set<BlockPosition> physical = new java.util.LinkedHashSet<>();
            for (var marker : evidence) {
                var observed = world.anchor(marker);
                if (!world.area().contains(observed)) continue;
                int axisValue = repeatedX ? observed.x() : observed.z();
                int minimum = repeatedX ? world.area().minX() : world.area().minZ();
                int index = (axisValue - minimum) / candidate.step();
                int delta = (candidate.mother() - index) * candidate.step();
                if (!shift(observed, candidate.axis(), delta).equals(anchor)) continue;
                var actual = shift(marker.position(), candidate.axis(), delta);
                if (!mother.contains(actual)
                        || storage.stream().anyMatch(bounds -> bounds.contains(actual))) continue;
                covered.add(index);
                physical.add(actual);
            }
            AreaBounds area = point(anchor);
            if ((long) anchor.y() + 1 <= mother.maxY()) {
                var above = point(anchor.offset(0, 1, 0));
                if (storage.stream().noneMatch(bounds -> overlaps(bounds, above))) {
                    area = enclosing(area, above);
                }
            }
            for (var actual : physical) area = enclosing(area, point(actual));
            long score = covered.size() * 1000000L - distance(anchor, box);
            result.add(new CategoryScanAssignment.Choice(anchor, area, score, Set.copyOf(covered)));
        }
        return result.stream().sorted(Comparator.comparingLong(
                CategoryScanAssignment.Choice::score).reversed()).toList();
    }

    private static List<List<CategoryScanAssignment.Choice>> alternatives(
            List<List<CategoryScanAssignment.Choice>> options, CategoryScanAssignment.Result initial) {
        var selected = new ArrayList<List<CategoryScanAssignment.Choice>>();
        var covered = new ArrayList<Set<Integer>>();
        var used = new java.util.HashSet<BlockPosition>();
        for (var choice : initial.choices()) {
            selected.add(new ArrayList<>(List.of(choice)));
            covered.add(new java.util.HashSet<>(choice.slices()));
            used.add(choice.anchor());
        }
        while (true) {
            var remaining = new ArrayList<List<CategoryScanAssignment.Choice>>();
            var owners = new ArrayList<Integer>();
            for (int i = 0; i < options.size(); i++) {
                if (initial.ambiguous().contains(i)) continue;
                var known = covered.get(i);
                var choices = options.get(i).stream().filter(choice -> !used.contains(choice.anchor()))
                        .filter(choice -> choice.slices().stream()
                                .anyMatch(slice -> !known.contains(slice)))
                        .map(choice -> new CategoryScanAssignment.Choice(choice.anchor(), choice.area(),
                                choice.score() - choice.slices().size() * 1000000L
                                        + choice.slices().stream()
                                        .filter(slice -> !known.contains(slice)).count() * 1000000L,
                                choice.slices())).toList();
                if (!choices.isEmpty()) { owners.add(i); remaining.add(choices); }
            }
            if (remaining.isEmpty()) break;
            var next = CategoryScanAssignment.assign(remaining);
            boolean changed = false;
            for (int i = 0; i < next.choices().size(); i++) {
                if (next.ambiguous().contains(i)) continue;
                var choice = next.choices().get(i);
                int owner = owners.get(i);
                selected.get(owner).add(choice);
                covered.get(owner).addAll(choice.slices());
                used.add(choice.anchor());
                changed = true;
            }
            if (!changed) break;
        }
        return selected;
    }

    private static long distance(BlockPosition p, AreaBounds area) {
        return Math.max(0L, Math.max((long) area.minX() - p.x(), (long) p.x() - area.maxX()))
                + Math.max(0L, Math.max((long) area.minY() - p.y(), (long) p.y() - area.maxY()))
                + Math.max(0L, Math.max((long) area.minZ() - p.z(), (long) p.z() - area.maxZ()));
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
        var expected = storageBlocks(scan);
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
                            || block.partner() != null && slab.contains(block.partner()))
                    .filter(block -> expected.stream().anyMatch(template -> shapeKey(
                            template.shift(scan.axis(), delta)).equals(shapeKey(block)))
                            || !displayContainer(scan, block, delta)).toList();
            var shifted = expected.stream().map(block -> block.shift(scan.axis(), delta))
                    .toList();
            String difference = difference(shifted, actual);
            if (difference != null) { failures.put(index, difference); continue; }
            List<CategoryZoneClassifier.Scan> groupScans = new ArrayList<>();
            for (var template : scan.templates()) {
                var storage = shift(template.storageArea(), scan.axis(), scan.delta(index));
                var targets = shifted.stream().filter(block -> storage.contains(block.bounds())).toList();
                if (targets.isEmpty()) {
                    failures.put(index, "组「" + template.name() + "」的储存范围没有容器：" + storage);
                    break;
                }
                var options = template.optionsEnabled() ? template.categoryOptions() : global;
                var effectiveMarkers = world.markers().stream()
                        .filter(marker -> matchesMarker(scan, template, delta, marker,
                                world.anchor(marker)))
                        .filter(marker -> shifted.stream().noneMatch(block ->
                                block.bounds().contains(marker.position()))).toList();
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
                scan.blocks(), slices, scan.canvasSize(), scan.markerAnchors()), bindings);
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
        var containers = storageBlocks(scan);
        if (containers.isEmpty()) throw new IllegalArgumentException("扫描母版没有储存容器");
        for (var block : scan.blocks().stream().filter(CategoryScanBlock::container).toList()) {
            long owners = scan.templates().stream().filter(template ->
                    template.storageArea().contains(block.bounds())).count();
            if (owners > 1 || owners == 0 && !displayContainer(scan, block, 0)) {
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
        int count = storageBlocks(scan).size();
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

    private static BlockPosition shift(BlockPosition p, String axis, int delta) {
        return new BlockPosition(Math.addExact(p.x(), axis.equals("X") ? delta : 0), p.y(),
                Math.addExact(p.z(), axis.equals("Z") ? delta : 0));
    }

    private static AreaBounds point(BlockPosition p) {
        return new AreaBounds(p.x(), p.y(), p.z(), p.x(), p.y(), p.z());
    }

    private static AreaBounds enclosing(AreaBounds a, AreaBounds b) {
        return new AreaBounds(Math.min(a.minX(), b.minX()), Math.min(a.minY(), b.minY()),
                Math.min(a.minZ(), b.minZ()), Math.max(a.maxX(), b.maxX()),
                Math.max(a.maxY(), b.maxY()), Math.max(a.maxZ(), b.maxZ()));
    }

    public static List<CategoryScanBlock> storageBlocks(CategoryScanDefinition scan) {
        return scan.blocks().stream().filter(CategoryScanBlock::container).filter(block ->
                scan.templates().stream().anyMatch(template ->
                        template.storageArea().contains(block.bounds()))).toList();
    }

    private static boolean displayContainer(CategoryScanDefinition scan, CategoryScanBlock block,
            int delta) {
        if (scan.templates().stream().anyMatch(template -> overlaps(
                shift(template.storageArea(), scan.axis(), delta), block.bounds()))) return false;
        return scan.templates().stream().anyMatch(template ->
                shift(template.markerArea(), scan.axis(), delta).contains(block.bounds()));
    }

    public static boolean matchesMarker(CategoryScanDefinition scan,
            CategoryBindingDefinition template,
            int delta, CategoryMarker marker, BlockPosition anchor) {
        if (!shift(template.markerArea(), scan.axis(), delta).contains(marker.position())) return false;
        var selected = scan.markerAnchors().get(template.id());
        if (selected == null) return true;
        if (selected.stream().anyMatch(p -> shift(p, scan.axis(), delta).equals(anchor))) return true;
        if (!marker.position().equals(anchor)) return false;
        // Preserve conflicts between two independent blocks in one declared region, while keeping
        // markers assigned to an adjacent group's logical slot out of this group's alternatives.
        return scan.markerAnchors().values().stream().flatMap(List::stream)
                .noneMatch(other -> shift(other, scan.axis(), delta).equals(anchor));
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
