# Stowling

Stowling is a Fabric client mod for Minecraft 26.2. It provides named
coordinates, corner-defined regions, and manually started linear tasks backed
by Baritone.

## Features

- Coordinate entries contain a name and X/Y/Z, with optional dimension and
  arrival-radius fields.
- Regions contain a name and two full X/Y/Z corner points.
- Tasks contain a name and one or more ordered module steps.
- Task modules cover movement, dropped-item collection, region collection,
  container scanning, category identification, cached-item sorting, and
  depositing.
- The recommended workflow uses two independent tasks: drops to a physical
  cache, then categorized distribution from that cache.
- Category zones are manually configured as ordered marker-area to
  storage-area bindings.
- The first rule reads non-air block IDs in the marker area and requires an
  exact match with the stored item ID.
- The coarse stage stores all collected drops in cache containers. The fine
  stage withdraws only items that have target categories and deposits them
  into exact category matches.
- Category identification reads nearby item frames or glow item frames and
  uses the displayed item registry ID as the category. Deposit prefers a
  container with an exact category match.
- Items without a category or without target capacity remain in, or are
  returned to, the cache.
- One task can run at a time.
- Coordinate and task IDs are generated internally and are not shown in the UI.
- Authentication, runtime status, and read-only inventory diagnostics remain
  available in the local Web console.

The previous monolithic pickup workflow, storage routes, audits, packing,
crafting, and scheduling APIs are intentionally not compatible.

## Task modules

The backend module catalog is available at `GET /v1/task-modules`; the task
editor builds its controls from that catalog.

- `MOVE_TO_COORDINATE` navigates to one coordinate.
- `WAIT_FOR_DROPPED_ITEMS` waits until a matching dropped item appears in the
  coordinate radius.
- `COLLECT_DROPPED_ITEMS` navigates to matching dropped items and waits until
  they are picked up.
- `COLLECT_DROPPED_ITEMS_IN_REGION` collects matching drops from a region.
- `SCAN_CONTAINERS_IN_REGION` records usable containers from the previous scan
  in the active task run.
- `MATCH_CONTAINER_CATEGORIES` reads nearby item frames and builds a run-local
  category mapping for the scanned containers.
- `SORT_CACHE_ITEMS` withdraws categorized items from a cache region, deposits
  exact category matches, and returns leftovers to the cache.
- `SORT_CACHE_ITEMS_BY_CATEGORY_ZONE` resolves manual category zones and stores
  cached items whose IDs exactly match the marker block IDs.
- `DEPOSIT_ITEMS_TO_CONTAINERS` deposits only items collected during the
  current run into containers from the previous scan.

The coarse cache task is:

```text
COLLECT_DROPPED_ITEMS_IN_REGION
    regionId=<drop area>
SCAN_CONTAINERS_IN_REGION
    regionId=<cache area>
DEPOSIT_ITEMS_TO_CONTAINERS
    scanSource=previous-scan
    itemSource=collected-this-run
    fillMode=nearest-first
```

The fine category-sorting task is:

```text
SCAN_CONTAINERS_IN_REGION
    regionId=<target storage area>
MATCH_CONTAINER_CATEGORIES
    scanSource=previous-scan
SORT_CACHE_ITEMS_BY_CATEGORY_ZONE
    cacheRegionId=<cache area>
    categoryZoneId=<category zone>
```

Example category-zone A binding:

```text
marker area:  7 -34 7 through 18 -34 7
storage area: 7 -34 6 through 18 -34 5
rule:         exact block ID
```

Add one binding per row or storage group. Multiple different marker block IDs in
one area may map to the same storage area. Keep structural blocks outside the
marker area because every non-air block ID is treated as a category.

Detection and scanning are limited to the player's current dimension and
loaded chunks. Supported ordinary containers are scanned; nested shulker-box
contents are not treated as independent containers. A double chest is recorded
once using one representative position. Categories match only the displayed
item registry ID, so display items with the same ID but different names or
components cannot be distinguished yet. Category mappings exist only for the
current task run and are never written to configuration. Cache and target
regions must not overlap.

## Configuration

The runtime file is `config/stowling.json`. Schema version 7 is the only accepted
format:

```json
{
  "schemaVersion": 7,
  "coordinates": [],
  "tasks": [],
  "regions": [],
  "categoryZones": []
}
```

If the file is missing, invalid, or from an older schema, Stowling creates a
`.bak` safety copy when possible and replaces it with an empty schema-7 file.
Old data is never migrated.

Coordinates use the player's current dimension unless an explicit dimension is
enabled. A disabled dimension or radius is returned as `null`; dropped-item
modules use their built-in search radius when radius is disabled. Tasks
snapshot their steps, coordinates, regions, and run-local scan results when
started, so editing saved resources only affects later runs.

## Web console

Start the game and open:

```text
http://127.0.0.1:8765/
```

Use the contents of `config/stowling-api.token` to connect.

Pages:

- **Status** shows the current run, coordinate count, task count, and history.
- **Coordinates** manages names and X/Y/Z values.
- **Category zones** manages marker areas, storage areas, and rules.
- **Tasks** provides cache and category-sorting templates or manual module
  composition.
- **Inventory** displays existing read-only inventory snapshots.

## HTTP API

All `/v1/*` requests require the Bearer token.

```text
GET    /v1/status
GET    /v1/inventory
GET    /v1/task-modules
GET    /v1/category-rules

GET    /v1/coordinates
POST   /v1/coordinates
GET    /v1/coordinates/{uuid}
PUT    /v1/coordinates/{uuid}
DELETE /v1/coordinates/{uuid}

GET    /v1/regions
POST   /v1/regions
GET    /v1/regions/{uuid}
PUT    /v1/regions/{uuid}
DELETE /v1/regions/{uuid}

GET    /v1/category-zones
POST   /v1/category-zones
GET    /v1/category-zones/{uuid}
PUT    /v1/category-zones/{uuid}
DELETE /v1/category-zones/{uuid}

GET    /v1/tasks
POST   /v1/tasks
GET    /v1/tasks/{uuid}
PUT    /v1/tasks/{uuid}
DELETE /v1/tasks/{uuid}
POST   /v1/tasks/{uuid}/runs

GET    /v1/task-runs
POST   /v1/task-runs/{uuid}/cancel
```

Updates and deletes include the current `revision`. A stale revision returns
`409`. Deleting a coordinate referenced by any task returns `409` with the
referencing task names.

## Development

```powershell
.\gradlew.bat assemble --console=plain --no-daemon
```

The main JAR is under `build/libs/` and does not contain `-sources` in its name.
