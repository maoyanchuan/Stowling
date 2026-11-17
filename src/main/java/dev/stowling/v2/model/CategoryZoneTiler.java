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
            List<CategoryBindingDefinition> templates
    ) {
        Objects.requireNonNull(expansion, "expansion");
        Objects.requireNonNull(templates, "templates");
        if (templates.size() != expansion.templateCount()) {
            throw new IllegalArgumentException(
                    "单片母版分类物数量与配置不一致"
            );
        }
        int maximumSpan = 1;
        for (CategoryBindingDefinition template : templates) {
            int markerSpan = span(template.markerArea(), expansion.direction());
            int storageSpan = span(template.storageArea(), expansion.direction());
            if (markerSpan != storageSpan) {
                throw new IllegalArgumentException(
                        "每个分类物的识别区和储存区在展开方向上的长度必须一致"
                );
            }
            if (!expansion.area().contains(template.markerArea())
                    || !expansion.area().contains(template.storageArea())) {
                throw new IllegalArgumentException(
                        "单片母版必须完整位于总选区内"
                );
            }
            maximumSpan = Math.max(maximumSpan, markerSpan);
        }
        if (expansion.step() < maximumSpan) {
            throw new IllegalArgumentException(
                    "单片步长不能小于单片在该方向上的长度"
            );
        }
        List<CategoryBindingDefinition> result = new ArrayList<>();
        for (int index = 0; ; index++) {
            int delta = offset(expansion, index);
            List<CategoryBindingDefinition> shifted = templates.stream()
                    .map(template -> shift(template, expansion, delta))
                    .toList();
            if (shifted.stream().anyMatch(binding ->
                    !expansion.area().contains(binding.markerArea())
                            || !expansion.area().contains(
                                    binding.storageArea()
                            ))) {
                break;
            }
            if (index >= MAX_SLICES) {
                throw new IllegalArgumentException(
                        "自动铺满最多生成 " + MAX_SLICES + " 个单片"
                );
            }
            for (int templateIndex = 0;
                    templateIndex < templates.size();
                    templateIndex++) {
                CategoryBindingDefinition template = templates.get(templateIndex);
                CategoryBindingDefinition shiftedBinding =
                        shifted.get(templateIndex);
                result.add(index == 0
                        ? template
                        : copyForOffset(
                                template,
                                shiftedBinding,
                                expansion,
                                delta
                        ));
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
                template.containerOverrides(),
                template.standingArea() == null ? null : template.standingArea().shift(x, 0, z)
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
                overrides,
                shifted.standingArea()
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
