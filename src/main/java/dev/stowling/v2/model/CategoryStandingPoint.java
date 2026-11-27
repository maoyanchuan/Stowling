package dev.stowling.v2.model;

/** A support block and its optional paired recognition/storage subregions. */
public record CategoryStandingPoint(
        CategoryPositionDraft position,
        AreaBounds markerArea,
        AreaBounds storageArea,
        boolean expanded,
        Boolean subregionsEnabled
) {
    public CategoryStandingPoint(CategoryPositionDraft position, AreaBounds markerArea,
            AreaBounds storageArea, boolean expanded) {
        this(position, markerArea, storageArea, expanded, null);
    }

    public boolean usesSubregions() {
        return subregionsEnabled == null ? markerArea != null || storageArea != null
                : subregionsEnabled;
    }

    public AreaBounds feetArea() {
        if (position == null) throw new IllegalArgumentException("请填写站立脚下方块坐标");
        var block = position.blockPosition();
        if (block.y() == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("站立脚下方块 Y 坐标过大");
        }
        return new AreaBounds(block.x(), block.y() + 1, block.z(),
                block.x(), block.y() + 1, block.z());
    }

    public CategoryStandingPoint shift(int x, int z) {
        var shifted = position == null ? null : new CategoryPositionDraft(
                shifted(position.x(), x), position.y(), shifted(position.z(), z));
        return new CategoryStandingPoint(shifted,
                markerArea == null ? null : markerArea.shift(x, 0, z),
                storageArea == null ? null : storageArea.shift(x, 0, z), expanded,
                subregionsEnabled);
    }

    private static Integer shifted(Integer coordinate, int delta) {
        if (coordinate == null) return null;
        try {
            return Math.addExact(coordinate, delta);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("自动铺满后的站位坐标超出范围", exception);
        }
    }
}
