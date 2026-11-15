# Stowling

Stowling is a Fabric client mod for Minecraft 26.2. The current version provides
named coordinate points and manually started, linear movement tasks backed by
Baritone.

## Features

- Coordinate entries contain a name and X/Y/Z, with optional dimension and
  arrival-radius fields.
- Regions contain a name and two full X/Y/Z corner points.
- Tasks contain a name and one or more ordered module steps.
- The first built-in module is `MOVE_TO_COORDINATE`.
- One task can run at a time.
- Coordinate and task IDs are generated internally and are not shown in the UI.
- Authentication, runtime status, and read-only inventory diagnostics remain
  available in the local Web console.

The previous pickup workflows, dimensions, radius/area ranges, containers,
storage routes, audits, packing, crafting, and scheduling APIs are intentionally
not compatible.

## Configuration

The runtime file is `config/stowling.json`. Schema version 7 is the only accepted
format:

```json
{
  "schemaVersion": 7,
  "coordinates": [],
  "tasks": [],
  "regions": []
}
```

If the file is missing, invalid, or from an older schema, Stowling creates a
`.bak` safety copy when possible and replaces it with an empty schema-7 file.
Old data is never migrated.

Coordinates are interpreted in the player's current dimension when a movement
task runs unless a coordinate enables an explicit dimension. A disabled
dimension or radius is returned as `null`; tasks interpret a missing dimension
as unrestricted and a missing radius as their built-in arrival tolerance. A
task snapshots its steps and target coordinates when started, so editing saved
coordinates only affects later runs.

## Web console

Start the game and open:

```text
http://127.0.0.1:8765/
```

Use the contents of `config/stowling-api.token` to connect.

Pages:

- **Status** shows the current run, coordinate count, task count, and history.
- **Coordinates** manages names and X/Y/Z values.
- **Tasks** combines module steps, saves tasks, runs them, and cancels runs.
- **Inventory** displays existing read-only inventory snapshots.

## HTTP API

All `/v1/*` requests require the Bearer token.

```text
GET    /v1/status
GET    /v1/inventory
GET    /v1/task-modules

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
