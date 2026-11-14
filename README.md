# Stowling

English | [简体中文](README_zh-CN.md)

Stowling is a Minecraft 26.2 Fabric client mod for pickup, container transfer, inventory
auditing, and shulker-box packing automation. It uses Baritone for navigation and is
designed to run in a normal client or a HeadlessMC client.

Current functionality includes:

- Baritone-backed pickup navigation, ground-item collection, storage routing, and overflow
  handling.
- Server-confirmed transfers for chest, barrel, hopper, and shulker-box menus.
- Inventory audits with loose, boxed, unknown, and nested shulker-box contents.
- Configurable shulker-box packing workcells with item-count or occupancy thresholds.
- SQLite persistence for tasks, recovery checkpoints, containers, and inventory snapshots.
- A named-coordinate library plus spherical or cuboid pickup regions, named cache
  containers, and reusable multi-region pickup workflows that reference those coordinates.
- A browser-based modular task builder with nine reusable robot modules, persisted workflow
  definitions, task progress, pause/resume/cancel controls, and restart checkpoints.
- A loopback REST control API with bearer authentication for status, tasks, pickup, audit,
  inventory, container, pause, resume, and config-reload operations.
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
kept as `config/stowling.json.bak`. Saving is blocked while a task is active or paused.

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

Omit `regionId` to run the complete workflow. Status data refreshes every 10 seconds, and
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
not expose a stable server-confirmed crafting transaction, so the module pauses with a
manual-recovery message instead of claiming success. Navigation, waits, pickup, inventory
checks, and container transfers are connected to the live client runtime.

The task builder saves workflows through these endpoints:

```text
GET /v1/automation-modules
GET /v1/config/automation-workflows
PUT /v1/config/automation-workflows
POST /v1/tasks/automation
```

Starting a task returns its workflow, current step, step index, and total step count.
Task state is checkpointed in SQLite. A recovered in-flight container operation is never
replayed automatically; it pauses for manual verification when its in-memory attempt no
longer exists.

The page is available only through the loopback listener. Static assets load without a
token, while every `/v1/*` API request still requires the correct Bearer token.

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
