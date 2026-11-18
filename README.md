# Stowling

Stowling is a Fabric client mod for Minecraft 26.2. It provides saved
coordinates, regions, category zones, queued tasks, and condition listeners.
Movement and container interaction are delegated through the existing game
ports and Baritone integration.

The coordinate page groups warehouse layouts, public facilities, basic points, and
basic regions. Resource lists stay on the left while the selected editor opens on
the right. Reference summaries show saved tasks and facilities using each resource.
Public facilities select named points rather than storing duplicate XYZ values;
referenced facility points cannot be deleted. Existing inline facility coordinates
and simple manual movement targets migrate to reusable named points on startup.
The task page selects locations and links to coordinate management. Each automatic
classification area selects its own storage mode, loose threshold, and partial-box
policy, while layouts retain standing and packing geometry shared by those tasks.

Temporary storage can combine named points and saved basic regions. At runtime,
regions are scanned for chests, trapped chests, and barrels; duplicate positions are
merged before nearby targets are tried. Referenced staging regions cannot be deleted.
The facility editor labels points and regions separately and preserves both types
when saved. Empty-box supply and fallback placement continue to use named points.

Category binding templates support loose, mixed, and box-only storage. Mixed storage
reserves 50% of the target's slots for loose items by default. The optional partial-box
switch stores unfinished boxes alongside full boxes and refills them before opening
another box. With the switch off, tails wait in the cache until a full box can be made.
Box capacity uses 27 times the item's actual stack limit.

Configure **全局储存与中断收尾** on the task or coordinate page before enabling box
storage: list temporary chests separately from empty-box supply chests, and enable
global cleanup. The optional fallback position is the block occupied by a placed box,
not the support block. Each binding can specify a packing area outside its selection;
the area moves with each generated slice. Otherwise, packing chooses a nearby clear
position with solid support and opening space. Inventory reserves two free slots during
cache withdrawal so a box can be acquired and recovered.

The shared runner cleanup waits for in-flight transfers, recovers registered boxes,
and deposits task-owned loose items and boxes into nearby temporary chests. Personal
inventory and working tools remain protected. Temporary chests participate in later
classification. If recovery or storage is blocked, the run retains its interruption
slot for retry; tasks do not need individual packing cleanup flows. Global storage
settings are persisted in `config/stowling-storage-settings.json`. Temporary world
resource ownership is currently kept for the active client session; finish or cancel
tasks before shutting down the client.

The task page uses a two-column console. A narrow left rail keeps active runs,
queued runs, and listening tasks visible in urgent / normal / background priority
order. Its lower half shows page-session task state changes, with export, clear,
and pause-scroll controls (up to 300 entries). Pause-scroll does not pause tasks;
reloading the page starts a new log session. Run details and interruption recovery
remain available in a dialog.

The right side offers fixed Move to Coordinate and Auto Classification tasks. Saved tasks appear
at the top as compact rows showing their name, target, and priority, collapsed
by default. Opening a row reveals its name, manual XYZ or saved coordinate, and
priority controls, with save and enqueue actions. Collapsing and reopening keeps
unsaved inputs; switching to another task asks before discarding changes. The
lower task-selection area contains only task templates. Selecting a template
opens an unsaved draft above; saving turns it into a saved task row. The move form edits only tasks
whose full definition is one `ACTION` step with module `MOVE_TO_COORDINATE`; complex workflows and unsupported move configurations are not
partially rewritten. Manual and saved inputs are retained while switching source,
but only the active source is serialized. Default names for new tasks avoid all
saved task names, including hidden complex tasks, by adding a numeric suffix.
A manually entered duplicate name prompts for a change without overwriting tasks.

Auto Classification is one saved row containing two independent queued phases:

1. Listen for dropped items around a saved coordinate, collect them, and deposit
   only the collected items into the selected multi-container cache region.
2. At background priority, scan the cache, identify the selected category zone,
   and transfer classified items in inventory-sized batches. Unmatched items stay
   in the cache. Original inventory items are not part of the task ledger.

The task form provides four independent marker-source checkboxes: item frames,
shelves, placed blocks, and marker chest/barrel contents. Sources are selected in
this task, not selected again per category binding. Marker-container contents are
read before resolving categories only when that source is selected. Matching uses
the category zone's marker/storage geometry and exact item IDs, not nearby frames.
Selected sources merge their item-to-container mappings; container overrides are
applied once. Higher-priority collection can interrupt background sorting, which
returns managed items to the cache through a reusable cleanup task before requeue.
Cleanup failure remains visible and blocks unsafe continuation.

`/v1/auto-classify-tasks` manages each task group atomically. Its collection,
classification, cleanup, and listener definitions remain internal; only one
saved row is displayed. Save keeps the configured listener state; Start Listening
also schedules an initial background pass for existing cache items. Stop Listening
stops future triggers without canceling queued or running work. Editing or deleting a task group
is rejected while any of its phases is queued or running.

Move tasks check arrival using the player's block position. Manual XYZ and all
saved coordinates require the target block. A coordinate resource radius is no
longer an arrival tolerance; other modules such as dropped-item detection may
still use it. Navigation that stops short fails rather than reporting arrival.
Container output references retain interaction-range behavior.

The move loader independently reads `GET /v1/tasks` and `GET /v1/coordinates`.
The monitor loader still reads only `GET /v1/task-modules` and
`GET /v1/task-runs`; connection starts both loaders in parallel. Saving a new task
uses `POST /v1/tasks`, editing uses `PUT /v1/tasks/{id}` with the loaded snapshot
revision, and enqueueing uses `POST /v1/tasks/{id}/runs` with `{}`. The create
response supplies an exact `taskId` while retaining `revision` and `tasks`, so the
page never guesses the new task by name or by diffing IDs. The page never creates a
saved coordinate. Folder editing and free-form trigger editing are not restored; auto-classification
listeners are configured through their owning task.

Persisted task, trigger, folder, and reusable editor source data remain available
to the backend and editor code. Inventory query navigation and static web routes
are temporarily removed; inventory APIs, data, and source assets remain intact for
later restoration.

## Task model

Tasks contain a name, optional `folderId`, and ordered tree steps. Folder IDs
refer to persisted task folders; `null` means the task is ungrouped. Every step
has one kind:

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
`IF` and `REPEAT` accept optional `negate=true`. `HAS_MANAGED_ITEMS` can
narrow its ledger using `itemSource=collected/carried/managed`; omitted means `managed`.

## Basic modules

The common entries are `CONTAINER_INTERACTION` and independent movement.
Container interaction selects `COLLECT`, `FIND`, `READ`, `WITHDRAW`, or `DEPOSIT`
through `operation`. Compact steps remain compact in saved configuration and
compile into existing basic actions and loops when queued, freezing resources
and detector configuration. There is no new monolithic workflow executor.
`GET /v1/task-modules` retains basic actions and three listeners for old tasks.
The monitor reads the catalog only to translate runtime error names. The fixed move
selector separately reads the full task list, so reusable editor source remains in
the repository without being exposed on the task page.

Actions:

```text
CONTAINER_INTERACTION
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

Collect-and-store task definition:

1. Add a dropped-item detector with a saved detection coordinate and queue priority.
2. Add container interaction `COLLECT`, inheriting the detector's scope.
3. Add container interaction `DEPOSIT`, choosing a saved destination and `collected` source.

The detector only queues work; collection begins when the task gets its turn.
Detector inheritance requires exactly one dropped-item detector on the task and
inherits its item filter unless overridden. An unbound stored task can be kept but not run.
A coordinate addresses one container; a region tries its containers; a category zone
matches items to containers (marker rules still require an existing marker cache).
Remaining items after full or unmatched containers cause failure. Unmanaged inventory
is not included.

```text
CONTAINER_INTERACTION operation=COLLECT areaRef=detector
CONTAINER_INTERACTION operation=DEPOSIT targetRef=<saved-address> itemSource=collected
```

The region variant compiles into the existing basic actions:

```text
COLLECT_DROPPED_ITEMS
SCAN_CONTAINERS_IN_REGION
REPEAT HAS_CONTAINERS
    SELECT_CONTAINER
    IF HAS_MANAGED_ITEMS itemSource=collected
        MOVE output:container
        DEPOSIT_ITEMS_TO_CONTAINERS itemSource=collected
ASSERT_NO_MANAGED_ITEMS itemSource=collected
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

Supported schema 7 task configurations migrate without discarding saved resources.
These legacy module IDs are expanded on load into tree steps:

- `AUTO_EAT_AT_COORDINATE`
- `SORT_CACHE_ITEMS_BY_CATEGORY_ZONE`
- `SORT_CACHE_ITEMS`
- `REFRESH_CATEGORY_MARKER_CACHE`
- `COLLECT_DROPPED_ITEMS_IN_REGION`
- parameterless or prior-scan `DEPOSIT_ITEMS_TO_CONTAINERS`

A legacy `SORT_CACHE_ITEMS` without an explicit category zone is rejected with
a configuration error. Select a category zone before using that task; Stowling
does not infer a destination or fall back to nearby-frame matching. An unsupported
configuration reports an error and preserves the original file instead of
resetting resources.

Migrated category tasks receive a deterministic custom cleanup task
that returns managed items to the cache. Re-reading the configuration does not
replace a new basic classification task's `DEFAULT` mode or duplicate the
generated cleanup.

The old nearby-frame matcher and `MATCH_CONTAINER_CATEGORIES` module are removed.
`WAIT_FOR_DROPPED_ITEMS` remains accepted for legacy configuration compatibility.

## Task folders

Task folders are persisted configuration objects with a flat one-level
structure. A task belongs to at most one folder; configuration/API writes keep
the task and folder assignment together. Deleting a folder only dissolves the
group: its tasks remain and their `folderId` becomes `null`. Folder data remains
available to the backend, while the current web page does not expose folder
management or filtering controls.

## Queuing and triggers

One task runs at a time. Pending work is ordered by `BACKGROUND`, `NORMAL`, and
`URGENT` priority. Trigger-created tasks and `CREATE_TASK` steps use the same
queue.

Triggers are stored independently but bound to one saved target task, and a task
may have multiple triggers. Dropped items, food level, and cache updates are
supported. The web page no longer reads trigger configuration directly; run
snapshots still provide listening status for the queue and logs. Trigger
intervals and cooldowns retain their existing meaning.

Background tasks may requeue after completion. `requeueAfterInterruption`
controls whether an interrupted task returns to the queue after cleanup.

## Configuration

The active file is `config/stowling.json` with schema version 8. Existing schema 7
files retain their resources and are upgraded on load:

```json
{
  "schemaVersion": 8,
  "coordinates": [],
  "tasks": [],
  "regions": [],
  "categoryZones": [],
  "triggers": [],
  "taskFolders": [],
  "autoClassifyTasks": []
}
```

New task and step fields are optional when reading old files. Unknown fields,
duplicate step IDs, excessive nesting, dangling typed references, and invalid
interruption references are rejected. Configuration writes remain revision
checked, and API mutations preserve the existing revision-conflict protection.

## Build

Use Java 25 and the checked-in Gradle wrapper:

```powershell
.\gradlew.bat assemble
```
