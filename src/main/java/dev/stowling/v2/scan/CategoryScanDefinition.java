package dev.stowling.v2.scan;

import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.CategoryBindingDefinition;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import dev.stowling.domain.BlockPosition;

/** Separate from legacy expansion: templates never occupy slots in the effective binding list. */
public record CategoryScanDefinition(AreaBounds area, String dimension, String axis, int step,
        int templateIndex, int sliceCount, List<CategoryBindingDefinition> templates,
        List<CategoryScanBlock> blocks, List<CategoryScanSlice> slices, CanvasSize canvasSize,
        Map<UUID, List<BlockPosition>> markerAnchors) {
    public static final int MAX_VOLUME = 65536;

    public record CanvasSize(int columns, int rows) {
        public CanvasSize {
            if (columns < 1 || rows < 1 || columns > 4096 || rows > 4096) {
                throw new IllegalArgumentException("画布行列数必须在 1 到 4096 之间");
            }
        }
    }

    public CategoryScanDefinition(AreaBounds area, String dimension, String axis, int step,
            int templateIndex, int sliceCount, List<CategoryBindingDefinition> templates,
            List<CategoryScanBlock> blocks, List<CategoryScanSlice> slices, CanvasSize canvasSize) {
        this(area, dimension, axis, step, templateIndex, sliceCount, templates, blocks, slices,
                canvasSize, Map.of());
    }

    public CategoryScanDefinition(AreaBounds area, String dimension, String axis, int step,
            int templateIndex, int sliceCount, List<CategoryBindingDefinition> templates,
            List<CategoryScanBlock> blocks, List<CategoryScanSlice> slices) {
        this(area, dimension, axis, step, templateIndex, sliceCount, templates, blocks, slices, null);
    }

    public CategoryScanDefinition {
        validateArea(area);
        Objects.requireNonNull(dimension, "dimension");
        if (dimension.isBlank() || !Set.of("X", "Z").contains(axis) || step < 1) {
            throw new IllegalArgumentException("扫描方向、维度或间距无效");
        }
        int length = axis.equals("X") ? area.maxX() - area.minX() + 1
                : area.maxZ() - area.minZ() + 1;
        if (sliceCount < 2 || sliceCount > 256 || sliceCount != (length + step - 1) / step
                || templateIndex < 0 || templateIndex >= sliceCount) {
            throw new IllegalArgumentException("扫描片数或母版位置无效");
        }
        templates = List.copyOf(templates);
        blocks = List.copyOf(blocks);
        slices = List.copyOf(slices);
        markerAnchors = markerAnchors == null ? Map.of() : markerAnchors.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())));
        if (templates.isEmpty() || templates.size() > 256 || blocks.size() > 4096) {
            throw new IllegalArgumentException("扫描母版为空或过大");
        }
        var mother = slab(area, axis, step, templateIndex);
        var ids = new HashSet<>();
        for (var template : templates) {
            if (!ids.add(template.id()) || !mother.contains(template.markerArea())
                    || !mother.contains(template.storageArea())) {
                throw new IllegalArgumentException("母版组标识重复或区域越出母版片");
            }
        }
        if (!ids.containsAll(markerAnchors.keySet())
                || markerAnchors.values().stream().anyMatch(List::isEmpty)
                || markerAnchors.values().stream().flatMap(List::stream)
                        .anyMatch(anchor -> !mother.contains(anchor))) {
            throw new IllegalArgumentException("逻辑展示位必须关联有效母版组并位于单片内");
        }
        var occupied = new HashSet<>();
        for (var block : blocks) {
            if (!mother.contains(block.bounds()) || !occupied.add(block.position())
                    || block.partner() != null && !occupied.add(block.partner())) {
                throw new IllegalArgumentException("母版方块越界或重叠");
            }
        }
        if (!slices.isEmpty()) {
            if (slices.size() != sliceCount) throw new IllegalArgumentException("扫描片结果不完整");
            for (int i = 0; i < slices.size(); i++) {
                var bounds = slab(area, axis, step, i);
                var origin = new dev.stowling.domain.BlockPosition(
                        bounds.minX(), bounds.minY(), bounds.minZ());
                if (slices.get(i).index() != i || !slices.get(i).origin().equals(origin)) {
                    throw new IllegalArgumentException("扫描片位置或顺序无效");
                }
            }
        }
    }

    public static void validateArea(AreaBounds area) {
        Objects.requireNonNull(area, "area");
        try {
            long volume = Math.multiplyExact(Math.multiplyExact(
                    (long) area.maxX() - area.minX() + 1,
                    (long) area.maxY() - area.minY() + 1),
                    (long) area.maxZ() - area.minZ() + 1);
            if (volume > MAX_VOLUME) throw new ArithmeticException();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("扫描总选区体积不能超过 " + MAX_VOLUME, exception);
        }
    }

    public static AreaBounds slab(AreaBounds area, String axis, int step, int index) {
        int x = axis.equals("X") ? Math.addExact(area.minX(), Math.multiplyExact(step, index))
                : area.minX();
        int z = axis.equals("Z") ? Math.addExact(area.minZ(), Math.multiplyExact(step, index))
                : area.minZ();
        return new AreaBounds(x, area.minY(), z,
                axis.equals("X") ? (int) Math.min(area.maxX(), (long) x + step - 1) : area.maxX(),
                area.maxY(),
                axis.equals("Z") ? (int) Math.min(area.maxZ(), (long) z + step - 1) : area.maxZ());
    }

    public AreaBounds mother() {
        return slab(area, axis, step, templateIndex);
    }

    public int delta(int index) {
        return Math.multiplyExact(index - templateIndex, step);
    }

    public CategoryScanDefinition withTemplates(List<CategoryBindingDefinition> values) {
        Map<UUID, List<BlockPosition>> anchors = new java.util.LinkedHashMap<>();
        for (int i = 0; i < Math.min(values.size(), templates.size()); i++) {
            var prior = templates.get(i);
            var value = values.get(i);
            if (prior.markerArea().equals(value.markerArea())
                    && markerAnchors.containsKey(prior.id())) {
                anchors.put(value.id(), markerAnchors.get(prior.id()));
            }
        }
        return new CategoryScanDefinition(area, dimension, axis, step, templateIndex, sliceCount,
                values, blocks, List.of(), canvasSize, anchors);
    }
}
