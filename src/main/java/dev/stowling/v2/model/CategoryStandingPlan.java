package dev.stowling.v2.model;

import java.util.ArrayList;
import java.util.List;

/** Group-owned spatial configuration, independent of business-option inheritance. */
public record CategoryStandingPlan(boolean enabled, List<CategoryStandingPoint> points) {
    public CategoryStandingPlan {
        points = points == null ? List.of() : List.copyOf(points);
    }

    public void validate(AreaBounds markers, AreaBounds storage) {
        if (!enabled) return;
        if (points.isEmpty()) throw new IllegalArgumentException("请至少添加一个站立位置");
        List<AreaBounds> used = new ArrayList<>();
        for (CategoryStandingPoint point : points) {
            point.feetArea();
            if (point.usesSubregions() && (point.markerArea() == null || point.storageArea() == null)) {
                throw new IllegalArgumentException("请填写子识别区与子储存区的完整坐标");
            }
            AreaBounds marker = point.usesSubregions() ? point.markerArea() : markers;
            AreaBounds target = point.usesSubregions() ? point.storageArea() : storage;
            if (!markers.contains(marker) || !storage.contains(target)) {
                throw new IllegalArgumentException("站位子区必须位于本绑定组的对应区域内");
            }
            if (used.stream().anyMatch(area -> overlaps(area, target))) {
                throw new IllegalArgumentException("多个站位的子储存区不能重叠，请划分负责范围");
            }
            used.add(target);
        }
    }

    private static boolean overlaps(AreaBounds left, AreaBounds right) {
        return left.minX() <= right.maxX() && left.maxX() >= right.minX()
                && left.minY() <= right.maxY() && left.maxY() >= right.minY()
                && left.minZ() <= right.maxZ() && left.maxZ() >= right.minZ();
    }

    public CategoryStandingPlan shift(int x, int z) {
        return new CategoryStandingPlan(enabled, points.stream()
                .map(point -> point.shift(x, z)).toList());
    }
}
