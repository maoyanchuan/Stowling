# Stowling

Stowling is a Fabric client mod for Minecraft 26.2. It provides saved
coordinates, regions, category zones, queued tasks, and condition listeners.
Movement and container interaction are delegated through the existing game
ports and Baritone integration.

## Task model

Tasks contain ordered tree steps. Every step has one kind:

- `ACTION` runs one basic module.
- `IF` evaluates a condition and runs `steps` or `otherwise`.
- `REPEAT` evaluates a condition and runs `steps` until it becomes false or
  `maxIterations` is reached. The default limit is `1024`.
- `CREATE_TASK` creates an independent task in the shared priority queue. It
  does not execute a child task inline.

Supported conditions are:

```text
ALWAYS
FOOD_BELOW_THRESHOLD
HAS_CONTAINERS
HAS_CARRIED_ITEMS
HAS_MANAGED_ITEMS
CACHE_HAS_MORE
HAS_MARKER_CONTAINERS
```

`FOOD_BELOW_THRESHOLD` uses `foodLevelThreshold` from `0` to `20`.
`IF` and `REPEAT` accept optional `negate=true`.

## Basic modules

The module catalog at `GET /v1/task-modules` exposes only real actions and the
three existing listeners. Internal scanning, selection, and tracking are not
declared as fake preset or composite modules.

Actions:

```text
MOVE_TO_COORDINATE
COLLECT_DROPPED_ITEMS
SCAN_CONTAINERS_IN_REGION
RESOLVE_CONTAINER_CATEGORIES
SELECT_CONTAINER
WITHDRAW_ITEMS
SELECT_STORAGE_TARGET
DEPOSIT_ITEMS_TO_CONTAINERS
WITHDRAW_FOOD
EAT_FOOD
SCAN_CATEGORY_MARKERS
SELECT_MARKER_CONTAINER
READ_CONTAINER
SAVE_CATEGORY_MARKER_CACHE
ASSERT_NO_MANAGED_ITEMS
```

Listeners:

```text
DROPPED_ITEMS
FOOD_LEVEL_AT_OR_BELOW
CACHE_UPDATED
```

`WITHDRAW_ITEMS` currently supports only `itemSource=classified`.
`DEPOSIT_ITEMS_TO_CONTAINERS` supports `collected`, `carried`, and `managed`.
The catalog and validator do not advertise options the runtime cannot enforce.

## Resource references

All task references use one encoded form:

```text
coordinate:<uuid>
region:<uuid>
category-zone:<uuid>
task:<uuid>
output:container
output:storage
output:marker
```

Parameter keys are unified:

- `targetRef` for movement, withdrawal, deposit, and food-container interaction.
- `areaRef` for coordinate-radius or region collection.
- `regionRef` for region scanning.
- `categoryZoneRef` for category resolution and marker refresh.
- `taskRef` for task creation and interruption references.

On load and save, legacy keys such as `coordinateId`, `regionId`,
`cacheRegionId`, `categoryZoneId`, `area=...`, and `previous-scan` are converted
to the current form where the original meaning is unambiguous. Existing schema
7 resources are retained rather than cleared.

## Interruption

Task interruption is configured per task:

```json
{
  "interruptionMode": "DEFAULT",
  "interruptionTaskRef": null,
  "defaultInterruptionFlow": false,
  "requeueAfterInterruption": false
}
```

Modes:

- `DEFAULT` uses the one task marked `defaultInterruptionFlow=true`, when
  present.
- `CUSTOM` uses `interruptionTaskRef=task:<uuid>`.
- `NONE` skips cleanup.

At most one task may be the shared default interruption flow. That task must
use `NONE`, cannot repeat when idle, and cannot contain `CREATE_TASK`.

Cleanup runs in the original execution slot with the original run workspace. It
is not inserted into the ordinary queue. Already moved items stay recorded so a
failed cleanup can be retried without losing item ownership. A run in
`INTERRUPTION_FAILED` can be recovered through:

```text
POST /v1/task-runs/{runId}/retry-interruption
POST /v1/task-runs/{runId}/skip-interruption
```

These routes use the same Bearer authentication as the rest of the control API.
Invalid run state returns `409 invalid_state`.

## Built-in workflow templates

Auto eat:

```text
IF FOOD_BELOW_THRESHOLD
    MOVE targetRef=coordinate
    REPEAT FOOD_BELOW_THRESHOLD
        WITHDRAW_FOOD
        EAT_FOOD
```

Collect and store:

```text
COLLECT_DROPPED_ITEMS
SCAN_CONTAINERS_IN_REGION
REPEAT HAS_CONTAINERS
    SELECT_CONTAINER
    MOVE output:container
    DEPOSIT_ITEMS_TO_CONTAINERS itemSource=collected
ASSERT_NO_MANAGED_ITEMS
```

Category sort:

```text
SCAN_CONTAINERS_IN_REGION regionRef=<cache>
RESOLVE_CONTAINER_CATEGORIES
REPEAT HAS_CONTAINERS
    SELECT_CONTAINER resetCacheHasMore=true
    REPEAT CACHE_HAS_MORE
        MOVE output:container
        WITHDRAW_ITEMS itemSource=classified
        REPEAT HAS_CARRIED_ITEMS
            SELECT_STORAGE_TARGET
            MOVE output:storage
            DEPOSIT_ITEMS_TO_CONTAINERS itemSource=carried
ASSERT_NO_MANAGED_ITEMS
```

Each `CACHE_HAS_MORE` iteration moves back to the cache container before
withdrawing because the previous inner loop may have left the player at a
storage container.

Marker refresh:

```text
SCAN_CATEGORY_MARKERS
REPEAT HAS_MARKER_CONTAINERS
    SELECT_MARKER_CONTAINER
    MOVE output:marker
    READ_CONTAINER
SAVE_CATEGORY_MARKER_CACHE
```

Default cleanup template:

```text
SCAN_CONTAINERS_IN_REGION
REPEAT HAS_CONTAINERS
    SELECT_CONTAINER
    MOVE output:container
    DEPOSIT_ITEMS_TO_CONTAINERS itemSource=managed
ASSERT_NO_MANAGED_ITEMS
```

## Legacy migration

Saved schema 7 configurations remain valid. These legacy module IDs are
expanded on load into tree steps:

- `AUTO_EAT_AT_COORDINATE`
- `SORT_CACHE_ITEMS_BY_CATEGORY_ZONE`
- `SORT_CACHE_ITEMS`
- `REFRESH_CATEGORY_MARKER_CACHE`
- `COLLECT_DROPPED_ITEMS_IN_REGION`
- parameterless or prior-scan `DEPOSIT_ITEMS_TO_CONTAINERS`

A legacy `SORT_CACHE_ITEMS` without an explicit category zone keeps its original
previous-scan semantics by starting with `MATCH_CONTAINER_CATEGORIES`; Stowling
does not infer a destination from an unrelated category zone.

Migrated category tasks receive a deterministic, editable custom cleanup task
that returns managed items to the cache. Re-reading the configuration does not
replace a new basic classification task's `DEFAULT` mode or duplicate the
generated cleanup.

`WAIT_FOR_DROPPED_ITEMS` and `MATCH_CONTAINER_CATEGORIES` remain accepted only
for legacy configuration compatibility and previous-scan migration.

## Queuing and triggers

One task runs at a time. Pending work is ordered by `BACKGROUND`, `NORMAL`, and
`URGENT` priority. Trigger-created tasks and `CREATE_TASK` steps use the same
queue.

Triggers are stored independently and support dropped items, food level, and
cache updates. Trigger intervals and cooldowns retain their existing meaning.

Background tasks may requeue after completion. `requeueAfterInterruption`
controls whether an interrupted task returns to the queue after cleanup.

## Configuration

The active file is `config/stowling.json` with schema version 7:

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

New task and step fields are optional when reading old files. Unknown fields,
duplicate step IDs, excessive nesting, dangling typed references, and invalid
interruption references are rejected. Configuration writes remain revision
checked, and API mutations preserve the existing revision-conflict protection.

## Build

Use Java 25 and the checked-in Gradle wrapper:

```powershell
.\gradlew.bat build
```
