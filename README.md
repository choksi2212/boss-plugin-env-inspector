# boss-plugin-env-inspector

A side panel for BOSS that lists every environment variable the host exposes, every system
property, and a curated set of runtime settings - all in one searchable view.

## Why it is unique

No plugin in the BOSS Plugin Store today answers "what does my BOSS process know about?". When
something misbehaves and the user wants to see whether a setting actually landed, whether an
env var made it through, or what the JVM reports for `user.home`, the answer today is to open
a terminal and shell out. This plugin makes that answer one click away in the sidebar.

## What it shows

- **System properties** from `System.getProperties()`
- **Process env vars** from `System.getenv()`
- **BOSS runtime** - a curated set of `boss.*` process properties plus JVM, OS and shell
  environment the BOSS host reads

Each category is hard-capped at 500 entries so a hostile host cannot make the collector allocate
gigabytes. Values over 4 KiB are truncated with an ellipsis.

## Secret masking

Every entry whose name matches `SECRET|TOKEN|KEY|PASSWORD|CREDENTIAL|API_KEY` (case
insensitive) is replaced with `<masked>` before the entry leaves the collector:

- The panel only ever shows `<masked>` for those keys
- The MCP `env_inspector_get(key)` tool returns `<masked>` for the same keys
- The MCP `env_inspector_reveal(key)` tool is the **only** path that can return a real
  secret value, and it requires the key to match the secret pattern - so a typo in the
  MCP call cannot leak a non-secret through. The call is recorded in the host's MCP ledger
  so an audit shows who unmasked what.

## MCP tools

| Tool | Description |
|---|---|
| `env_inspector_list` | List entries, optional `category` filter |
| `env_inspector_get` | Read one entry by key (masked if secret) |
| `env_inspector_reveal` | Explicit unmask for secret-looking keys (recorded in MCP ledger) |
| `env_inspector_diff` | Compare two saved snapshots by id, or `current` |

## Snapshots

The panel footer has a **Save** button that captures the current state into a labeled snapshot,
up to 10 in-memory + persisted. The diff tool answers "what changed?" with three lists:
added, removed, changed. Snapshots are persisted via `PluginStorageProvider`, keyed at
`env_inspector.snapshots.v1`, and restored on the next launch.

## Install

1. Download `boss-plugin-env-inspector-0.1.0.jar` from the latest release
2. Open BOSS - **Settings > Plugins > Install from File**
3. Pick the jar; the side panel appears under **Env Inspector** in the left rail

Or use the **Plugin Store** entry the release publishes (assuming the publish key is wired up).

## Compatibility

- **BOSS host**: `9.4.2` or newer (`minBossVersion` in the manifest)
- **Plugin API**: `1.0.93` or newer (`minApiVersion` in the manifest)

## Build

```bash
./gradlew clean buildPluginJar -x test --no-daemon
# -> build/libs/boss-plugin-env-inspector-0.1.0.jar
```

The release workflow on push to `main` builds the jar, cuts a GitHub release, and publishes to
the Plugin Store. Pull requests run `./gradlew build` against the latest plugin API release.

## License

Plugin code in this repo is released under the project's open-source terms; the BOSS
application itself is its own project. See the release you depend on for the exact license
header.
