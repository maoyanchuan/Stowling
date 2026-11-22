package dev.stowling.v2.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record CoordinateCatalog(List<TaskFolderDefinition> folders, List<Warehouse> warehouses,
        Map<String, UUID> placements) {
    public static final CoordinateCatalog EMPTY =
            new CoordinateCatalog(List.of(), List.of(), Map.of());

    public CoordinateCatalog {
        folders = folders == null ? List.of() : List.copyOf(folders);
        warehouses = warehouses == null ? List.of() : List.copyOf(warehouses);
        placements = placements == null ? Map.of() : Map.copyOf(placements);
    }

    public CoordinateCatalog editFolder(UUID id, String name) {
        var updated = new ArrayList<>(folders);
        updated.removeIf(folder -> folder.id().equals(id));
        if (name != null) updated.add(new TaskFolderDefinition(id, name));
        var locations = new LinkedHashMap<>(placements);
        if (name == null) locations.values().removeIf(id::equals);
        return new CoordinateCatalog(updated, warehouses, locations);
    }

    public CoordinateCatalog editWarehouse(UUID id, String name, List<String> members) {
        var updated = new ArrayList<>(warehouses);
        updated.removeIf(warehouse -> warehouse.id().equals(id));
        if (name != null) updated.add(new Warehouse(id, name, members));
        var catalog = new CoordinateCatalog(folders, updated, placements);
        return name == null ? catalog.move("warehouse:" + id, null) : catalog;
    }

    public CoordinateCatalog move(String ref, UUID folderId) {
        var updated = new LinkedHashMap<>(placements);
        if (folderId == null) updated.remove(ref);
        else updated.put(ref, folderId);
        return new CoordinateCatalog(folders, warehouses, updated);
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
