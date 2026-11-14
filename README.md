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
- A **Coordinates and regions** page for named coordinates and spherical or cuboid pickup
  regions; existing cache containers and multi-region pickup workflows remain compatible.
- A browser-based modular task builder with ten reusable robot modules, persisted workflow
  definitions, task progress, priority scheduling, cancellation, and restart-safe records.
- Versioned pickup and automation task definitions with schema 5 scheduling, priority,
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

The output jar is written under `build/libs/`.

## Configuration

The runtime config path is `config/stowling.json`. On first startup, Stowling writes the
bundled `src/main/resources/stowling.example.json` configuration when no file exists.
Durations are serialized as whole seconds. Validation rejects duplicate IDs, invalid
container or dimension references, invalid packing thresholds, and equal-priority
item-route conflicts.

The overflow container remains mandatory for legacy storage routing. Unmatched items in
that routing flow go there rather than being discarded. Pure `RUN_PICKUP` steps do not
implicitly route or store collected items.

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
page manages task definitions, while **Coordinates and regions** manages named points and
pickup regions. Tasks, coordinate points, and regions have separate save operations; there
is no shared **Save all** action for tasks and resources. Configuration writes are validated
and atomic, with the previous file retained as `config/stowling.json.bak`. Saving is blocked
while a scheduled run is active or the scheduler is unsafe.

### Coordinates and regions

Open `http://127.0.0.1:8765/coordinates.html` to add, delete, rename, or reorder named points
and edit their dimensions and X/Y/Z values. Coordinate IDs are immutable after creation.
Coordinate drafts are saved independently; save a point before selecting it for a region.
A successful coordinate save refreshes the region editor's saved points and references.

On the same page, define a named `SPHERE` region by selecting a saved center point and a
radius, or a `CUBOID` region by selecting two saved corner points. Cuboid corners may be
selected in either order: each axis is automatically sorted into an inclusive min/max
range. The editor shows names, dimensions, and XYZ or bounds summaries. References must
be in the same dimension. Saving a coordinate synchronizes its existing references;
referenced coordinates cannot be deleted. A region referenced by a task or legacy workflow
cannot be deleted either, and rejected operations display an explanatory error.

There is no standby-point or cache-container editor. Existing regions retain their legacy standby anchors in the same dimension. New regions
derive an internal compatibility anchor from the center or first corner; pure pickup does
not use these fields. Existing task, workflow, and cache configurations are retained, but their
legacy resource settings are not edited by the new page.

Named-coordinate endpoints are:

```text
GET /v1/config/coordinate-points
PUT /v1/config/coordinate-points
```

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

Open `http://127.0.0.1:8765/tasks.html` to compose reusable tasks from ten robot modules:

- `NAVIGATE`: move to a saved named point using `coordinateId`.
- `WAIT`: wait for the configured `seconds`.
- `RUN_PICKUP`: collect within a saved `regionId`, with `idleSeconds` and `waitForItems`.
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
items, or pack boxes. Compose movement, waiting, container operations, and packing as
separate steps. The task page selects saved regions and coordinates; it has no shared
cache, standby-point, or corner editor.

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

Configuration schema 5 stores scheduling settings with each pickup or automation workflow.
Existing schema 1-4 files are migrated automatically. Migrated and newly saved task
definitions start disabled; `autoStartOnRestart` defaults to `false`.

Each definition has a priority (`LOW`, `MEDIUM`, `HIGH`). The editor offers four triggers:

- `MANUAL`: only an explicit run request starts the task.
- `FIXED_INTERVAL`: runs after each positive `intervalSeconds` interval.
- `DAILY`: runs at one or more `HH:mm` times in the configured time zone.
- `AFTER_COMPLETION`: waits for the positive `intervalSeconds` delay after a successful
  run, then queues the task.

New **Run pickup** steps select a saved `regionId` from **Coordinates and regions**, with
`idleSeconds` (default `2`) and `waitForItems` (default `false`). `idleSeconds` ends the
round after that region has no items for the configured duration. Region geometry is
edited on the coordinates page, not inside a task. Pickup only collects items; any later
movement, wait, storage, transfer, or packing must be an explicit module step.

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

The **Coordinates and regions** page saves regions independently through the existing
task-resource endpoints:

```text
GET /v1/config/task-resources
PUT /v1/config/task-resources
```

The `GET` snapshot includes `revision`, `pickupRegions`, `cacheContainers`, and
`pickupWorkflows`. Before saving regions, the page reads the latest snapshot and sends a
`PUT` body containing its `revision`, the edited `pickupRegions`, and unchanged legacy
`cacheContainers`. It does not edit caches, discard old resources, or replace workflows
or task definitions. Revision conflicts are reported rather than silently overwritten.

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
robot audit. Named points and pickup regions are managed on **Coordinates and regions**.
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
