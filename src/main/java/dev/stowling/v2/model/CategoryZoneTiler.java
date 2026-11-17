package dev.stowling.v2.model;

import dev.stowling.domain.BlockPosition;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class CategoryZoneTiler {
    public static final int MAX_SLICES = 256;

    private CategoryZoneTiler() {
    }

    public static List<CategoryBindingDefinition> expand(
            CategoryZoneExpansionDefinition expansion,
            CategoryBindingDefinition template
    ) {
        Objects.requireNonNull(expansion, "expansion");
        Objects.requireNonNull(template, "template");
        int markerSpan = span(template.markerArea(), expansion.direction());
        int storageSpan = span(template.storageArea(), expansion.direction());
        if (markerSpan != storageSpan) {
            throw new IllegalArgumentException(
                    "分类物识别区和储存区在展开方向上的长度必须一致"
            );
        }
        if (expansion.step() < markerSpan) {
            throw new IllegalArgumentException(
                    "单片步长不能小于单片在该方向上的长度"
            );
        }
        if (!expansion.area().contains(template.markerArea())
                || !expansion.area().contains(template.storageArea())) {
            throw new IllegalArgumentException(
                    "单片母版必须完整位于总选区内"
            );
        }
        List<CategoryBindingDefinition> result = new ArrayList<>();
        int index = 0;
        while (index < MAX_SLICES) {
            int delta = offset(expansion, index);
            CategoryBindingDefinition shifted = shift(template, expansion, delta);
            if (!expansion.area().contains(shifted.markerArea())
                    || !expansion.area().contains(shifted.storageArea())) {
                break;
            }
            result.add(index == 0
                    ? template
                    : copyForOffset(template, shifted, expansion, delta));
            index++;
        }
        if (index == MAX_SLICES) {
            CategoryBindingDefinition overflow = shift(
                    template,
                    expansion,
                    offset(expansion, MAX_SLICES)
            );
            if (expansion.area().contains(overflow.markerArea())
                    && expansion.area().contains(overflow.storageArea())) {
                throw new IllegalArgumentException(
                        "自动铺满最多生成 " + MAX_SLICES + " 个单片"
                );
            }
        }
        return List.copyOf(result);
    }

    private static int span(
            AreaBounds area,
            CategoryZoneExpansionAxis direction
    ) {
        return switch (direction) {
            case X_POSITIVE, X_NEGATIVE -> area.maxX() - area.minX() + 1;
            case Z_POSITIVE, Z_NEGATIVE -> area.maxZ() - area.minZ() + 1;
        };
    }

    private static int offset(
            CategoryZoneExpansionDefinition expansion,
            int index
    ) {
        long distance = (long) index * expansion.step();
        if (distance > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("自动铺满单片步长过大");
        }
        return switch (expansion.direction()) {
            case X_POSITIVE, Z_POSITIVE -> (int) distance;
            case X_NEGATIVE, Z_NEGATIVE -> -(int) distance;
        };
    }

    private static CategoryBindingDefinition shift(
            CategoryBindingDefinition template,
            CategoryZoneExpansionDefinition expansion,
            int delta
    ) {
        int x = expansion.direction() == CategoryZoneExpansionAxis.X_POSITIVE
                || expansion.direction()
                == CategoryZoneExpansionAxis.X_NEGATIVE
                ? delta
                : 0;
        int z = x == 0 ? delta : 0;
        return new CategoryBindingDefinition(
                template.id(),
                template.markerArea().shift(x, 0, z),
                template.storageArea().shift(x, 0, z),
                template.ruleId(),
                template.ignoredMarkerBlockIds(),
                template.containerOverrides()
        );
    }

    private static CategoryBindingDefinition copyForOffset(
            CategoryBindingDefinition template,
            CategoryBindingDefinition shifted,
            CategoryZoneExpansionDefinition expansion,
            int delta
    ) {
        List<CategoryContainerOverrideDefinition> overrides =
                template.containerOverrides().stream()
                        .map(override -> shiftedOverride(
                                override,
                                expansion,
                                delta
                        ))
                        .toList();
        return new CategoryBindingDefinition(
                derivedId(template.id(), expansion, delta),
                shifted.markerArea(),
                shifted.storageArea(),
                template.ruleId(),
                template.ignoredMarkerBlockIds(),
                overrides
        );
    }

    private static CategoryContainerOverrideDefinition shiftedOverride(
            CategoryContainerOverrideDefinition override,
            CategoryZoneExpansionDefinition expansion,
            int delta
    ) {
        int x = expansion.direction() == CategoryZoneExpansionAxis.X_POSITIVE
                || expansion.direction()
                == CategoryZoneExpansionAxis.X_NEGATIVE
                ? delta
                : 0;
        int z = x == 0 ? delta : 0;
        BlockPosition position = override.storagePosition();
        return new CategoryContainerOverrideDefinition(
                derivedId(override.id(), expansion, delta),
                new BlockPosition(
                        shiftedCoordinate(position.x(), x),
                        position.y(),
                        shiftedCoordinate(position.z(), z)
                ),
                override.mode(),
                override.itemIds()
        );
    }

    private static int shiftedCoordinate(int value, int delta) {
        try {
            return Math.addExact(value, delta);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "自动铺满后的容器例外坐标超出范围",
                    exception
            );
        }
    }

    private static UUID derivedId(
            UUID templateId,
            CategoryZoneExpansionDefinition expansion,
            int delta
    ) {
        String value = templateId + ":auto:"
                + expansion.direction().name() + ":" + delta;
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
