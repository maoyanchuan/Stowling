# Stowling

Stowling is a Fabric client mod for Minecraft 26.2. It provides named
coordinates, corner-defined regions, and manually started linear tasks backed
by Baritone.

## Features

- Coordinate entries contain a name and X/Y/Z, with optional dimension and
  arrival-radius fields.
- Regions contain a name and two full X/Y/Z corner points.
- Tasks contain a name, priority, optional background repeat mode, and one or
  more ordered module steps.
- Module metadata marks each step as `BASIC` or `COMPOSITE`. A saved task with
  exactly one basic step appears under basic tasks; all other tasks appear under
  composite tasks.
- Task modules cover movement, dropped-item collection, region collection,
  container scanning, category identification, cached-item sorting, and
  depositing.
- `AUTO_EAT_AT_COORDINATE` checks hunger, moves to a configured food chest,
  takes one selected food, and eats until the threshold is met.
- The recommended workflow uses two independent tasks: drops to a physical
  cache, then categorized distribution from that cache.
- Category zones are manually configured as ordered marker-area to
  storage-area bindings.
- Category markers use one automatic display-item recognizer: item frames are
  read from entities, shelves from block-entity slots, and ordinary display
  blocks through their placement item.
- Each storage column must resolve to exactly one item ID. Configured support
  block IDs are ignored, and conflicting displays fail with a clear error.
- Per-container exceptions can append extra accepted item IDs or replace the
  automatic category entirely. Replacement rules bypass display ambiguity for
  that container.
- The `MARKER_CONTAINER_CONTENTS` rule reads item IDs from marker chests or
  barrels through a user-refreshed cache, allowing a double chest to define up
  to 54 distinct accepted item types.
- The coarse stage stores all collected drops in cache containers. The fine
  stage withdraws only items that have target categories and deposits them
  into exact category matches.
- Category identification reads item frames, shelves, and placed display
  blocks and uses the displayed item registry ID as the category. Deposit
  prefers a container with an exact category match.
- Items without a category or without target capacity remain in, or are
  returned to, the cache.
- One task runs at a time. A single queue orders work by `BACKGROUND`, `NORMAL`,
  and `URGENT` priority.
- Independent task triggers poll conditions without occupying the execution
  slot. Dropped-item, food-level, and cache-update conditions are supported.
- A trigger submits its selected target task through the normal queue at the
  trigger's configured priority. The queue then orders all pending work by
  priority and request order.
- After a triggered run completes, the trigger waits for its configured cooldown
  before returning to idle monitoring.
- A strictly higher-priority request preempts the active task. The interrupted
  run is discarded, requeued, and starts again from step one when its turn
  returns.
- Coordinate and task IDs are generated internally and are not shown in the UI.
- Authentication, runtime status, and read-only inventory diagnostics remain
  available in the local Web console.

The previous monolithic pickup workflow, storage routes, audits, packing,
crafting, and scheduling APIs are intentionally not compatible.

## Task modules

The backend module catalog is available at `GET /v1/task-modules`; the task
editor builds its controls from that catalog.

The task list, run selector, module catalog, and step module selector are split
into basic and composite groups without changing existing task configuration.

Background tasks can automatically requeue five seconds after completion.
Manual runs may override the saved priority for that run only.

Task triggers are configured independently from tasks and automatic requeue.
Idle triggers create no run records and do not block another task.

New tasks expose seven modules. Older modules remain readable for existing
configurations but are hidden from the module catalog when creating a step.

- `MOVE_TO_COORDINATE` navigates to one coordinate.
- `COLLECT_DROPPED_ITEMS` collects matching drops from either a coordinate
  radius or a saved region.
- `SCAN_CONTAINERS_IN_REGION` records usable containers from the previous scan
  in the active task run.
- `AUTO_EAT_AT_COORDINATE` skips immediately when food is sufficient; otherwise
  it navigates to the configured food chest, takes one food at a time, and eats
  until the threshold is met.
- `SORT_CACHE_ITEMS_BY_CATEGORY_ZONE` resolves automatic display markers and
  stores cached items whose IDs exactly match the recognized item IDs.
- `REFRESH_CATEGORY_MARKER_CACHE` opens the category marker chests or barrels,
  reads their slots without moving items, and updates the persistent cache.
- `DEPOSIT_ITEMS_TO_CONTAINERS` deposits only items collected during the
  current run into containers from the previous scan. This module has no
  configurable parameters.

`WAIT_FOR_DROPPED_ITEMS`, `COLLECT_DROPPED_ITEMS_IN_REGION`,
`MATCH_CONTAINER_CATEGORIES`, and `SORT_CACHE_ITEMS` are deprecated compatibility
modules for existing tasks. The dropped-item trigger replaces the wait module;
the category-zone module replaces the two old category modules.

The coarse cache task is:

```text
COLLECT_DROPPED_ITEMS
    area=region:<drop area>
SCAN_CONTAINERS_IN_REGION
    regionId=<cache area>
DEPOSIT_ITEMS_TO_CONTAINERS
    no parameters
```

The fine category-sorting task is:

```text
SORT_CACHE_ITEMS_BY_CATEGORY_ZONE
    cacheRegionId=<cache area>
    categoryZoneId=<category zone>
```

Use `area=coordinate:<coordinate>` instead when the collection range should use
a saved coordinate radius. The category template creates a one-shot `NORMAL`
task; the cache-update trigger decides when it runs.

When a category zone uses `MARKER_CONTAINER_CONTENTS`, run the following module
before sorting whenever the sample items in the marker container change:

```text
REFRESH_CATEGORY_MARKER_CACHE
    categoryZoneId=<category zone>
```

The cache is stored in `config/stowling-category-marker-cache.json`. Sorting
never refreshes it automatically; a missing cache fails with a prompt to run the
refresh module.

The auto-eat module uses `minecraft:bread`-style item IDs and a coordinate for
the food chest. When food is low, it opens that chest, moves exactly one food to
the hotbar or offhand, closes the chest, and eats. It repeats until the food
level reaches the threshold. The optional threshold defaults to 18.

Example category-zone A binding:

```text
marker area:  7 -34 7 through 18 -34 7
storage area: 7 -34 6 through 18 -34 5
rule:         automatic display item
ignored:      minecraft:stone_bricks
exception:    8 -34 5, ADD, minecraft:oak_planks
```

Add one binding per row or storage group. The marker area may be several blocks
high or wide and may mix placed blocks, supported displays, item frames, and
shelves. List shared support or background block IDs in the ignored list.
Every storage column must contain only one distinct displayed item ID.

Container exceptions target one storage position. `ADD` keeps the automatic
category and adds the listed item IDs. `REPLACE` ignores that container's
automatic category and accepts only the listed IDs. This is useful for a chest
that stores a small related group or for a column where the support material
is also the displayed category.

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
  "categoryZones": [],
  "triggers": []
}
```

Triggers are stored independently from tasks and select a target task:

```json
{
  "id": "00000000-0000-0000-0000-000000000004",
  "name": "Low hunger",
  "type": "FOOD_LEVEL_AT_OR_BELOW",
  "targetTaskId": "00000000-0000-0000-0000-000000000002",
  "priority": "NORMAL",
  "intervalTicks": 20,
  "cooldownTicks": 100,
  "foodLevelThreshold": 18
}
```

A dropped-item trigger uses a coordinate instead:

```json
{
  "id": "00000000-0000-0000-0000-000000000004",
  "name": "Pickup drops",
  "type": "DROPPED_ITEMS",
  "targetTaskId": "00000000-0000-0000-0000-000000000002",
  "priority": "NORMAL",
  "intervalTicks": 20,
  "cooldownTicks": 100,
  "coordinateId": "00000000-0000-0000-0000-000000000001",
  "itemFilter": "minecraft:oak_log,minecraft:birch_log"
}
```

The item filter is optional and accepts comma-separated full item IDs or item
paths. An empty filter matches every dropped item. Dropped-item triggers use the
coordinate's configured dimension and radius, falling back to radius 16 when
radius is disabled.

A cache-update trigger starts only after `DEPOSIT_ITEMS_TO_CONTAINERS` actually
moves at least one item into the selected cache region:

```json
{
  "id": "00000000-0000-0000-0000-000000000004",
  "name": "Sort cache after deposit",
  "type": "CACHE_UPDATED",
  "targetTaskId": "00000000-0000-0000-0000-000000000002",
  "priority": "NORMAL",
  "intervalTicks": 40,
  "cooldownTicks": 100,
  "cacheRegionId": "00000000-0000-0000-0000-000000000003"
}
```

`intervalTicks` is the debounce window for this trigger type. Consecutive
deposits in the same region therefore create one sort run. If that task is
already queued or running, the trigger keeps one pending follow-up and schedules
it after the current run and cooldown finish.

```text
trigger condition matches
    -> selected task enters the shared queue
    -> queue orders it by trigger priority
    -> task runs
    -> trigger cooldown
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

- **Status** shows the current run, trigger status, coordinate count, task count,
  and history.
- **Coordinates** groups points, regions, and category-zone setup in one
  submenu. Points and regions manage reusable geometry; category zones manage
  marker areas, storage areas, and rules.
- **Tasks** provides cache and category-sorting templates, manual module
  composition, and independent task triggers.
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

GET    /v1/task-triggers
POST   /v1/task-triggers
GET    /v1/task-triggers/{uuid}
PUT    /v1/task-triggers/{uuid}
DELETE /v1/task-triggers/{uuid}

GET    /v1/task-runs
POST   /v1/task-runs/{uuid}/cancel
```

`GET /v1/task-runs` returns `runs`, `queue`, and `triggers`. Trigger states are
`IDLE`, `RUNNING`, `COOLDOWN`, and `ERROR`.

Updates and deletes include the current `revision`. A stale revision returns
`409`. Deleting a coordinate referenced by a task or trigger returns `409`.
Deleting a task targeted by a trigger also returns `409`.

## Development

```powershell
.\gradlew.bat assemble --console=plain --no-daemon
```

The main JAR is under `build/libs/` and does not contain `-sources` in its name.
