# Stowling

Stowling is a Minecraft 26.2 Fabric client mod for pickup, container transfer, inventory
auditing, and shulker-box packing automation. It uses Baritone for navigation and is
designed to run in a normal client or a HeadlessMC client.

This repository currently contains the phase-one foundation only. The automation state
machine, SQLite persistence, REST API, and HeadlessMC launcher integration are represented
by stable interfaces for later phases.

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

The planned runtime config path is `config/stowling.json`. A documented skeleton is
available at `src/main/resources/stowling.example.json`. Durations are serialized as
whole seconds. Validation rejects duplicate IDs, invalid container/dimension references,
invalid packing thresholds, and equal-priority item-route conflicts.

The overflow container is mandatory. Unmatched items must be routed there rather than
discarded. The first release is restricted to one dimension and never automatically
opens shulker boxes.

## Security boundaries

Authentication tokens, Microsoft account data, chat content, and other personally
identifiable information must never be logged or stored in the mod config. The future
REST listener must bind to `127.0.0.1` and authenticate every sensitive endpoint with a
Bearer token.

## Quality rules

- Four-space indentation
- Maximum line length of 100 columns
- Explicit types on all methods
- JUnit tests for Java behavior and pytest as the top-level acceptance command
- Conventional Commits whose body explains why the change is needed
