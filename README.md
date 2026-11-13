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
