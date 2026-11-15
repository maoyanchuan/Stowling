<p align="center">
    <img src="src/main/resources/assets/stowling/icon.svg" alt="Stowling" width="96">
</p>

# Stowling

English | [简体中文](README_zh-CN.md)

Stowling is a Minecraft 26.2 Fabric client mod for pickup, container transfer, inventory
auditing, and shulker-box packing automation. It uses Baritone for navigation and is
designed to run in a normal client or a HeadlessMC client.

Current functionality includes:

- Baritone-backed navigation and ground-item collection, with explicit container and packing
  modules; legacy storage routing and overflow handling remain compatible.
- Server-confirmed transfers for chest, barrel, hopper, and shulker-box menus.
- Existing inventory snapshots with loose, boxed, unknown, and nested shulker-box contents.
  Legacy capture reads only an explicitly identified, already-open container.
- Configurable shulker-box packing workcells with item-count or occupancy thresholds.
- SQLite persistence for tasks, recovery checkpoints, containers, and inventory snapshots.
- A **Coordinates and ranges** library for points, spherical or cubic radius ranges,
  and two-corner areas; task selectors display only their names.
- A browser-based modular task builder with ten reusable robot modules, persisted workflow
  definitions, task progress, priority scheduling, cancellation, and restart-safe records.
- Versioned pickup and automation task definitions with schema 6 scheduling, priority,
  restart, trigger, and interruption settings.
- A loopback REST control API with bearer authentication for status, tasks, pickup, audit,
  inventory, container, task scheduling, and config-reload operations.
- In-client HeadlessMC control commands plus Windows start, status, stop, retry, and log
  rotation scripts.

## Toolchain

- Java 25
- Gradle 9.8.2 through the checked-in wrapper
- Minecraft 26.2
- Fabric Loader 0.20.1
- Fabric API 0.161.0+26.2
- Loom 1.19.4
- Baritone API for Fabric 1.19.0

The official Fabric API and Baritone versions are pinned in `gradle.properties` and
`libs/README.md`. The Baritone jar is compile-only and is not embedded in Stowling's jar.

## Development

Run the Java tests and style checks:

```powershell
.\gradlew.bat test checkstyleMain checkstyleTest
```

Run the unified acceptance entry point:

```powershell
pytest
```

Build the remapped mod:

```powershell
.\gradlew.bat build
```

The output jar is written under `build/libs/`. Only the main JAR whose filename does not
contain `-sources` belongs in the game's `mods` directory. The sources JAR is for inspection
and debugging and must not be installed.

## Configuration

The runtime config path is `config/stowling.json`. On first startup, Stowling writes the
bundled `src/main/resources/stowling.example.json` configuration when no file exists.
Durations are serialized as whole seconds. Validation rejects duplicate IDs, invalid
container or dimension references, invalid packing thresholds, and equal-priority
item-route conflicts.

The overflow container remains mandatory for legacy storage routing. Unmatched items in
that routing flow go there rather than being discarded. Pure `RUN_PICKUP` steps do not
implicitly route or store collected items. The `PICKUP_TO_CONTAINER` preset runs one
pickup round and deposits only the inventory gained during that round.

## Web console

When Stowling starts, its local REST service also serves a browser control console. Open
the following address on the same computer as the running game:

```text
http://127.0.0.1:8765/
```

Paste the contents of `config/stowling-api.token` from the game instance into the page and
select **Verify and connect**. The token is kept only in the current tab's
`sessionStorage`; it is never added to the URL, cookies, project configuration, or logs.
Use **Clear token** to remove it from the current browser session.

The console shows runtime status, tasks, containers, and inventory snapshots. The task
page manages task definitions, while **Coordinates and ranges** manages saved points and geometry.
Tasks and coordinate points have separate save operations; there is no shared **Save all**
action. Configuration writes are validated and atomic, with the previous file retained as
`config/stowling.json.bak`. Saving is blocked while a scheduled run is active or unsafe.

### Coordinates and ranges

Open `http://127.0.0.1:8765/coordinates.html` and choose **Add coordinate** to create,
delete, rename, or reorder entries and edit their dimensions and X/Y/Z values. Entry IDs are
immutable after creation. Each entry first chooses **Coordinate** or **Area**:

- **Coordinate**: select **Point only** (`POINT`), **Sphere radius** (`SPHERE`), or
  **Cube radius** (`CUBE`). Radius ranges use 1–128 blocks and XYZ is the center. A cube
  extends by the radius on each axis, while a sphere uses distance from the center.
- **Area** (`AREA`): enter first and second XYZ corners in the same dimension. No radius is
  shown. Corner order does not matter and bounds are normalized. Navigation and container
  steps anchor an area at the center of the two corners.

The existing `position` field is retained, with `type`, an optional numeric `radius`, and an
optional `secondCorner` added to coordinate entries. Save the definition before selecting it
in a task. **Run pickup** accepts radius ranges and areas and only stores `coordinateId`,
not a separate radius or region. Updating the saved radius or corners applies automatically
on the next run, so the task does not need to reselect the same ID.
On an already open task page, **Refresh tasks and coordinates** reloads saved coordinates
without discarding unsaved task edits. Automatic status refresh does not reload resources.
There is no separate pickup-region panel.

The editor shows names, dimensions, and XYZ values. Saving a coordinate synchronizes its
existing references. Confirming deletion of a saved point immediately persists that deletion
atomically and keeps a backup. Other unsaved point edits remain in the draft and are not
submitted with it. The confirmation lists only actual associated regions, legacy caches, and
legacy pickup tasks; custom tasks are never removed as a side effect. Direct or indirect
custom-task references, including interruption steps, still block deletion. Save and deletion
errors show the actual reason.

New installations start with no sample named points, regions, caches, pickup flows, storage
routes, or audit schedules. On startup, existing configurations retire the old `north-farm`,
`north-cache`, and default pickup-flow chain and persist the cleanup with a backup. User
resources are retained; if a custom task actually depends on that chain, it is left intact
rather than silently deleting the user's task. Historical examples exist only in test fixtures.

There is no separate region, standby-point, or cache-container editor. Existing region, task,
workflow, and cache configurations remain compatible and are not removed by this UI change.
No replacement region-management page is introduced.

Named-coordinate endpoints are:

```text
GET /v1/config/coordinate-points
PUT /v1/config/coordinate-points
```

The coordinate update accepts an optional `removeLegacyReferencesFor` array containing
saved coordinate IDs explicitly confirmed for deletion in the same request. Omitting it
retains the original strict reference protection. Stale revisions or remaining task
references reject the entire update without removing any resources.

### Legacy pickup compatibility API

Existing pickup tasks can still run an entire legacy workflow or one region within it.
These endpoints are compatibility entry points, not the new modular pickup editor:

```text
GET /v1/config/pickup
PUT /v1/config/pickup
GET /v1/pickup-workflows
POST /v1/tasks/pickup
```

The legacy pickup request body uses `workflowId` and an optional `regionId`:

```json
{"workflowId":"farm_patrol","regionId":"north_farm"}
```

Omit `regionId` to run the complete workflow. Status data refreshes every 5 seconds, and
an expired token prompts for re-entry. Replace the installed jar and restart the client
before expecting a newly built version to take effect.

### Task builder

Open `http://127.0.0.1:8765/tasks.html` to compose reusable tasks from eleven robot modules:

- `NAVIGATE`: move to a saved named point using `coordinateId`.
- `WAIT`: wait for the configured `seconds`.
- `RUN_PICKUP`: select a saved named range with `coordinateId`; configure
  `idleSeconds` and `waitForItems`.
  Point-only coordinates are shown as disabled pickup choices with guidance to change
  them to a radius or area.
- `PICKUP_TO_CONTAINER`: select a saved pickup range and a point coordinate for the
  destination container. It picks one round, travels only when something was collected,
  and deposits only the collected inventory delta.
- `TAKE_ITEMS`: take `itemId`/`count` from `coordinateId`, with an optional `containerType`.
- `PUT_ITEMS`: put `itemId`/`count` into `coordinateId`, with an optional `containerType`.
- `TRANSFER_ITEMS`: move `itemId`/`count` between `sourceCoordinateId` and
  `destinationCoordinateId`, with an optional container type for each endpoint.
- Check the live player inventory.
- Acquire missing materials from the latest fresh container snapshot.
- Run a crafting step.
- `PACK_ITEMS`: pack items into shulker boxes using explicit workcell coordinates.

`PACK_ITEMS` is a real runtime module, not an implicit pickup side effect. Configure
`sourceCoordinateId`, `boxCoordinateId`, `outputCoordinateId`, and `fallbackCoordinateId`,
plus `itemId`, `count`, `completionTrigger`, and `completionThreshold`. It uses these named
points for the source, placed packing box, output, and fallback locations, with item-count or
occupancy completion thresholds.

Pure pickup does not implicitly move to a standby point, store items in caches, classify
items, or pack boxes. Use `PICKUP_TO_CONTAINER` for the common drop-to-chest flow, or
compose movement, waiting, container operations, and packing as separate steps. The task
page selects saved regions and coordinates; it has no shared cache, standby-point, or
corner editor.

The crafting module is intentionally conservative in this release. Ordinary clients do
not expose a stable server-confirmed crafting transaction, so the scheduled run stops with
a manual-handling message instead of claiming success. Navigation, waits, pickup, inventory
checks, container transfers, and packing are connected to the live client runtime.

The task builder saves workflows through these endpoints:

```text
GET /v1/automation-modules
GET /v1/task-definitions
POST /v1/task-definitions
GET /v1/task-definitions/{kind}/{id}
PUT /v1/task-definitions/{kind}/{id}
DELETE /v1/task-definitions/{kind}/{id}
POST /v1/task-definitions/{kind}/{id}/enable
POST /v1/task-definitions/{kind}/{id}/disable
POST /v1/task-definitions/{kind}/{id}/run
```

Starting a task returns its workflow, current step, step index, and total step count.
Task state is checkpointed in SQLite. After a restart, previous in-flight records are
failed and their checkpoints are discarded; container operations are never replayed.

The page is available only through the loopback listener. Static assets load without a
token, while every `/v1/*` API request still requires the correct Bearer token.

### Scheduled task definitions

Configuration schema 6 adds coordinate geometry alongside existing task scheduling settings.
Existing schema 1-5 files are migrated automatically. Migrated and newly saved task
definitions start disabled; `autoStartOnRestart` defaults to `false`.

Each definition has a priority (`LOW`, `MEDIUM`, `HIGH`). The editor offers four triggers:

- `MANUAL`: only an explicit run request starts the task.
- `FIXED_INTERVAL`: runs after each positive `intervalSeconds` interval.
- `DAILY`: runs at one or more `HH:mm` times in the configured time zone.
- `AFTER_COMPLETION`: waits for the positive `intervalSeconds` delay after a successful
  run, then queues the task.

New **Pickup and deposit** tasks preset one `PICKUP_TO_CONTAINER` step. It requires only a
saved drop range plus a point coordinate for the container, with optional container type,
`idleSeconds` (default `2`), and `waitForItems` (default `false`). Existing inventory is
preserved: only item counts above the pre-pickup baseline are transferred. If the range
yields nothing, the task finishes without navigating to the container.

Pure **Run pickup** steps select a named radius range or area using `coordinateId`.
The step no longer edits radius, shape, coordinates, or regions. Configure those in
**Coordinates and ranges**. Every run resolves the current saved geometry, so changes take
effect on the next run; unsaved drafts do not appear in task options.

Upgrading schema 1-5 preserves old pure `coordinateId` + `radius` and `regionId` pickup bounds
by moving them into typed coordinate resources and updating the step reference. Task IDs and
position anchors are retained; conflicting radii get distinct named resources rather than
silently changing another task's range. Legacy regions needed by old workflows are retained.
Startup writes the upgraded configuration atomically with a backup.

Both pickup steps support `waitForItems`; pure pickup also uses the round's `idleSeconds`
to decide when the target has no items for long enough. Pickup alone only collects items;
later movement, wait, storage, transfer, or packing must be explicit steps.

The optional **Wait for items before pickup** condition observes client-visible drops in
the selected sphere or cuboid once per second before starting. It never patrols or loads
chunks while waiting; unobservable regions show a waiting-for-visibility status. Waiting
belongs to the running task and can be safely interrupted. Each pickup round is finite,
and repeated execution is controlled by the parent task trigger.

Legacy task steps, pickup workflows, and cache settings remain readable and compatible.
The new editor does not offer legacy workflow or cache editing. Only an explicit,
confirmed conversion changes an old pickup step to pure `RUN_PICKUP` mode; opening or
saving an existing task does not silently convert it. A converted step does not retain
implicit standby movement, cache storage, classification, or packing behavior; add the
required actions as separate modules.

Legacy `ITEMS_PRESENT` task triggers remain supported and are shown explicitly as legacy
settings when editing existing configurations; new tasks use the pickup step condition.

An interruption can be `NONE`, an `INLINE` step list, or a `TASK_REFERENCE`. References
must target automation tasks, and missing-target and cycle validation is applied before
the configuration is saved.

The control service exposes the task-definition lifecycle through
`taskDefinitions`, `taskDefinition`, `createTaskDefinition`,
`updateTaskDefinition`, `deleteTaskDefinition`, `taskScheduling`,
`enableTaskDefinition`, `disableTaskDefinition`, and `runTaskDefinition`.
Every definition has its own revision, so updating one task does not replace unrelated
pickup or automation definitions. Saving or creating a definition does not enable it.

Existing task-resource endpoints remain available for compatibility:

```text
GET /v1/config/task-resources
PUT /v1/config/task-resources
```

The `GET` snapshot includes `revision`, `pickupRegions`, `cacheContainers`, and
`pickupWorkflows`. Tasks still read these resources to resolve existing region selections.
The coordinate page no longer edits them. Legacy API updates retain revision checks and
must not silently overwrite other resources or task definitions.

Scheduling advances only from `tickOnClientThread`. Legacy pickup and automation requests
enter the same priority queue as scheduled runs and cannot bypass a higher-priority queued
task. A preemption first calls `requestStop()` and waits for `pollStop()` to return
`CLEAN`; `PENDING` keeps the current task in control, while `UNSAFE` locks further
dispatch. Cancelling a scheduled run is supported. Global pause/resume was removed;
disable a definition to prevent future scheduling, and run a task again to start over.
Interrupted runs restart at the first step with a new execution ID; no saved step is resumed.
Interruption steps run after a clean stop and cannot themselves be preempted.

Reading or refreshing definitions does not enable tasks. SQLite stores enabled state, timers,
queued requests and execution history. On restart, only tasks previously enabled with
`autoStartOnRestart` restore scheduling; unfinished rounds restart at step one with a new
execution ID. Other tasks must be enabled again. Legacy checkpoints are discarded, and old
clicks are never replayed.

The warehouse page is inventory-only: refresh reads existing snapshots and never starts a
robot audit. **Coordinates and ranges** manages all point and geometry definitions.
Task steps only reference their saved names, with legacy resources retained for compatibility.
The task page does not edit shared caches, standby points, corners, or legacy warehouse
base coordinates. Existing legacy resources remain compatible through their configuration
and API entry points. Warehouse synchronization, area discovery, partitions, and
material-collection refresh are not implemented in this release; only module extension
ports are reserved.

## HeadlessMC operation

The Windows supervisor scripts can be inspected without launching HeadlessMC:

```powershell
.\scripts\Start-StowlingHeadless.ps1 -DryRun
.\scripts\Start-StowlingHeadless.ps1 -Status
.\scripts\Stop-StowlingHeadless.ps1
```

Automated tests do not launch HeadlessMC or connect to Minecraft services. A real-account,
real-server smoke test still requires an external HeadlessMC installation, compatible
runtime mods, account credentials, and access to a target server. Those credentials and
the HeadlessMC installation are not provided by this repository.

## Security boundaries

Authentication tokens, Microsoft account data, chat content, and other personally
identifiable information must never be logged or stored in the mod config. The REST
control listener binds to `127.0.0.1` and uses a bearer token stored in
`config/stowling-api.token`.

## Quality rules

- Four-space indentation
- Maximum line length of 100 columns
- Explicit types on all methods
- JUnit tests for Java behavior and pytest as the top-level acceptance command
- Conventional Commits whose body explains why the change is needed
