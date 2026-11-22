package dev.stowling.v2.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record CoordinateCatalog(List<TaskFolderDefinition> folders, List<Warehouse> warehouses,
        Map<String, UUID> placements, List<String> order) {
    public static final CoordinateCatalog EMPTY =
            new CoordinateCatalog(List.of(), List.of(), Map.of());

    public CoordinateCatalog {
        folders = folders == null ? List.of() : List.copyOf(folders);
        warehouses = warehouses == null ? List.of() : List.copyOf(warehouses);
        placements = placements == null ? Map.of() : Map.copyOf(placements);
        order = order == null ? List.of() : List.copyOf(order);
    }

    public CoordinateCatalog(List<TaskFolderDefinition> folders, List<Warehouse> warehouses,
            Map<String, UUID> placements) {
        this(folders, warehouses, placements, List.of());
    }

    public CoordinateCatalog editFolder(UUID id, String name) {
        var updated = new ArrayList<>(folders);
        var locations = new LinkedHashMap<>(placements);
        if (name == null) {
            updated.removeIf(folder -> folder.id().equals(id));
            locations.values().removeIf(id::equals);
            return new CoordinateCatalog(updated, warehouses, locations,
                    order.stream().filter(ref -> !ref.equals("folder:" + id)).toList());
        }
        var replacement = new TaskFolderDefinition(id, name);
        for (int i = 0; i < updated.size(); i++) {
            if (updated.get(i).id().equals(id)) {
                updated.set(i, replacement);
                return new CoordinateCatalog(updated, warehouses, locations, order);
            }
        }
        updated.add(replacement);
        return new CoordinateCatalog(updated, warehouses, locations, order);
    }

    public CoordinateCatalog editWarehouse(UUID id, String name, List<String> members) {
        var updated = new ArrayList<>(warehouses);
        updated.removeIf(warehouse -> warehouse.id().equals(id));
        if (name != null) updated.add(new Warehouse(id, name, members));
        var catalog = new CoordinateCatalog(folders, updated, placements, order);
        return name == null ? catalog.remove("warehouse:" + id) : catalog;
    }

    public CoordinateCatalog move(String ref, UUID folderId) {
        var updated = new LinkedHashMap<>(placements);
        if (folderId == null) updated.remove(ref);
        else updated.put(ref, folderId);
        return new CoordinateCatalog(folders, warehouses, updated, order);
    }

    public CoordinateCatalog remove(String ref) {
        var updated = new LinkedHashMap<>(placements);
        updated.remove(ref);
        return new CoordinateCatalog(folders, warehouses, updated,
                order.stream().filter(value -> !value.equals(ref)).toList());
    }

    public CoordinateCatalog place(String ref, UUID folderId, String beforeRef, List<String> ids) {
        var ordered = new LinkedHashSet<>(order);
        ordered.addAll(ids);
        if (!ordered.contains(ref) || folderId != null && ref.startsWith("folder:")) {
            throw new IllegalArgumentException("所选条目无法移动到此位置");
        }
        if (beforeRef != null && (!ordered.contains(beforeRef)
                || !Objects.equals(placements.get(beforeRef), folderId))) {
            throw new IllegalArgumentException("排序位置已变更，请刷新后重试");
        }
        if (ref.equals(beforeRef)) return this;
        var result = new ArrayList<>(ordered);
        result.remove(ref);
        result.add(beforeRef == null ? result.size() : result.indexOf(beforeRef), ref);
        return new CoordinateCatalog(folders, warehouses, move(ref, folderId).placements(), result);
    }

    public record Warehouse(UUID id, String name, List<String> members) {
        public Warehouse {
            Objects.requireNonNull(id, "id");
            name = new TaskFolderDefinition(id, name).name();
            var unique = new LinkedHashSet<String>();
            for (String member : members) {
                var ref = ResourceReference.parse(member);
                if (ref.type() != ResourceType.COORDINATE && ref.type() != ResourceType.REGION) {
                    throw new IllegalArgumentException("仓库成员必须是点位或普通区域");
                }
                unique.add(ref.encode());
            }
            if (unique.isEmpty() || unique.size() > 64) {
                throw new IllegalArgumentException("仓库需要 1 至 64 个点位或区域");
            }
            members = List.copyOf(unique);
        }
    }
}
