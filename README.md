<p align="center">
    <img src="src/main/resources/assets/stowling/icon.svg" alt="Stowling" width="96">
</p>

# Stowling

English | [简体中文](README_zh-CN.md)

Stowling is a Minecraft 26.2 Fabric client mod for pickup, container transfer, inventory
auditing, and shulker-box packing automation. It uses Baritone for navigation and is
designed to run in a normal client or a HeadlessMC client.

Current functionality includes:

- Baritone-backed pickup navigation, ground-item collection, storage routing, and overflow
  handling.
- Server-confirmed transfers for chest, barrel, hopper, and shulker-box menus.
- Existing inventory snapshots with loose, boxed, unknown, and nested shulker-box contents.
  Legacy capture reads only an explicitly identified, already-open container.
- Configurable shulker-box packing workcells with item-count or occupancy thresholds.
- SQLite persistence for tasks, recovery checkpoints, containers, and inventory snapshots.
- A named-coordinate library plus spherical or cuboid pickup regions, named cache
  containers, and reusable multi-region pickup workflows that reference those coordinates.
- A browser-based modular task builder with nine reusable robot modules, persisted workflow
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

The overflow container is mandatory. Unmatched items must be routed there rather than
discarded.

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

The console shows runtime status, tasks, containers, and inventory snapshots. Its
**Pickup configuration** editor manages named spherical or cuboid regions, named cache
containers, and workflows that combine regions and caches. Changes remain drafts until
**Save all** validates and atomically updates `config/stowling.json`; the previous file is
kept as `config/stowling.json.bak`. Saving is blocked while a scheduled run is active or
the scheduler is unsafe.

The separate **Named coordinates** page supports adding, deleting, renaming, and reordering
coordinate points with editable dimensions and X/Y/Z values. Coordinate IDs are immutable
after creation while display names remain editable. Region standby points, shape centers
and corners, and cache positions select saved coordinates instead of duplicating position
fields. Saving synchronizes all references, rejects cross-dimension references, and refuses
to delete a coordinate that is still referenced. The corresponding endpoints are:

```text
GET /v1/config/coordinate-points
PUT /v1/config/coordinate-points
```

A pickup task can run an entire workflow or one selected region from that workflow. The
corresponding REST endpoints are:

```text
GET /v1/config/pickup
PUT /v1/config/pickup
GET /v1/pickup-workflows
POST /v1/tasks/pickup
```

The pickup request body uses `workflowId` and an optional `regionId`:

```json
{"workflowId":"farm_patrol","regionId":"north_farm"}
```

Omit `regionId` to run the complete workflow. Status data refreshes every 5 seconds, and
an expired token prompts for re-entry. Replace the installed jar and restart the client
before expecting a newly built version to take effect.

### Task builder

Open `http://127.0.0.1:8765/tasks.html` to create reusable automation workflows from
these modules:

- Navigate to a named coordinate.
- Wait for a configured duration.
- Run a complete pickup workflow or one pickup region.
- Take items from a container into the player inventory.
- Put player items into a container.
- Transfer items between two containers.
- Check the live player inventory.
- Acquire missing materials from the latest fresh container snapshot.
- Run a crafting step.

The crafting module is intentionally conservative in this release. Ordinary clients do
not expose a stable server-confirmed crafting transaction, so the scheduled run stops with
a manual-handling message instead of claiming success. Navigation, waits, pickup, inventory
checks, and container transfers are connected to the live client runtime.

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

Each definition has a priority (`LOW`, `MEDIUM`, `HIGH`) and one of five triggers:

- `MANUAL`: only an explicit run request starts the task.
- `FIXED_INTERVAL`: runs after each positive `intervalSeconds` interval.
- `DAILY`: runs at one or more `HH:mm` times in the configured time zone.
- `AFTER_COMPLETION`: waits for the positive `intervalSeconds` delay after a successful
  run, then queues the task.
- `ITEMS_PRESENT`: watches the configured pickup regions and queues the task when items
  are observed. A pickup task may leave `regionIds` empty to watch its own regions;
  custom automation tasks must select one or more regions.

An interruption can be `NONE`, an `INLINE` step list, or a `TASK_REFERENCE`. References
must target automation tasks, and missing-target and cycle validation is applied before
the configuration is saved.

The control service exposes the task-definition lifecycle through
`taskDefinitions`, `taskDefinition`, `createTaskDefinition`,
`updateTaskDefinition`, `deleteTaskDefinition`, `taskScheduling`,
`enableTaskDefinition`, `disableTaskDefinition`, and `runTaskDefinition`.
Every definition has its own revision, so updating one task does not replace unrelated
pickup or automation definitions. Saving or creating a definition does not enable it.

Task-editing clients can manage reusable pickup regions and cache containers without
replacing workflows through these endpoints:

```text
GET /v1/config/task-resources
PUT /v1/config/task-resources
```

The `PUT` body contains `revision`, `pickupRegions`, and `cacheContainers`; it does not
contain or modify `pickupWorkflows`. Existing pickup and automation definitions are
preserved.

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
robot audit. Pickup regions, cache containers and warehouse base coordinates are edited on
the task page; the named-coordinate page remains separate. Warehouse synchronization, area
discovery, partitions and material-collection refresh are not implemented in this release;
only module extension ports are reserved.

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
