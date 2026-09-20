# AGENTS.md

Coding-agent guidance for this repo. Mirrors the structure of the git-status plugin repo
where it overlaps; deviations are noted where they matter.

## Project Overview

`boss-plugin-env-inspector` is a dynamic BOSS plugin that surfaces the host process's
environment variables, system properties, and a curated set of runtime settings in a single
searchable side panel, with a small MCP tool surface that lets in-terminal agents ask the
same questions programmatically.

It is plugin #13 in a serial build of 20 and must compile, build a jar, and pass CI green.

## Essential Commands

```bash
./gradlew clean buildPluginJar -x test --no-daemon   # Local build (uses sibling jar)
CI=true ./gradlew build -x test --no-daemon          # Simulates the CI test workflow
./gradlew build -x test --no-daemon                   # Full build (depends on buildPluginJar)
```

## File map

| Path | Purpose |
|---|---|
| `build.gradle.kts` | Single source of truth for `version`; `buildPluginJar` pins the API jar. |
| `settings.gradle.kts` | Standard Gradle config; matches git-status. |
| `src/main/resources/META-INF/boss-plugin/plugin.json` | Manifest; `version` is synced from `build.gradle.kts` at build time. |
| `src/main/kotlin/.../EnvInspectorDynamicPlugin.kt` | Entry point (`DynamicPlugin`). Owns the singletons. |
| `src/main/kotlin/.../EnvInspectorInfo.kt` | `PanelInfo` - slot `left.bottom`, order 78. |
| `src/main/kotlin/.../EnvInspectorComponent.kt` | `PanelComponentWithUI`; one view-model per placement. |
| `src/main/kotlin/.../EnvInspectorViewModel.kt` | Filter / search / selection state + actions. |
| `src/main/kotlin/.../EnvInspectorContent.kt` | Compose UI. |
| `src/main/kotlin/.../EnvCollector.kt` | Reads process state; applies secret masking. |
| `src/main/kotlin/.../EnvSnapshot.kt` | Data model (`@Serializable`). |
| `src/main/kotlin/.../SnapshotStore.kt` | Persisted list of snapshots, capped at 10. |
| `src/main/kotlin/.../EnvInspectorMcpTools.kt` | The four `env_inspector_*` MCP tools. |
| `.github/workflows/build.yml` | Release workflow on push to `main`; needs `permissions: contents: write`. |
| `.github/workflows/test.yml` | PR-only test workflow; downloads the API jar and runs `./gradlew build`. |

## Design notes

### Secret masking is a one-way door

`EnvCollector.maskIfSecret` is the single point that hides values. Once an entry has been
collected, the real value is never available without going through `EnvCollector.reveal`,
which:

- Refuses any key whose name does not match the secret pattern (so an MCP `reveal` call
  for a non-secret never returns the value).
- Is exposed through an MCP tool marked `readOnly = false`, so the host records the call in
  the MCP ledger with the key.

The panel and the `env_inspector_get` tool always see the masked form. The clipboard copy
from the panel copies the displayed value - never the real one.

### Bounding is load-bearing

- `MAX_ENTRIES_PER_CATEGORY = 500` (a hostile host with thousands of env vars cannot make
  the collector allocate gigabytes).
- `MAX_KEY_LENGTH = 256`, `MAX_VALUE_LENGTH = 4 KiB` (the latter truncates with an
  explicit ellipsis so the user can see the value was clipped).

`SnapshotStore.load` defensively caps to 10 entries on read - a hand-edited storage file
cannot blow out memory on next launch.

### The reveal tool is `readOnly = false`

It is the only MCP tool in this plugin that mutates state in the sense the host's policy
cares about: it lets the agent see a value the plugin deliberately hid. The host treats
that as a deliberate action and writes it to the MCP ledger. The tool itself never logs
the value, and the response text contains the value because the agent needs it - but no
log line, no exception message, and no error path carries a real secret.

### `PluginStorageProvider` is `compileOnly`

The host provides it at runtime, so we treat it as nullable in the dynamic plugin's
`register`. When it is null, the snapshot store is null and the UI degrades to "Save"
being a no-op (with a status message explaining why). No crash.

### The view-model owns filter state, not the panel

Two windows with the same panel placement each get their own `EnvInspectorViewModel`
because the panel factory creates a new view-model per `PanelComponentWithUI`. Filter and
search state does not bleed across windows.

### `Modifier.weight` only works inside `ColumnScope`

`EnvEntryList` takes the modifier as a parameter; the caller (inside a `Column` block)
supplies `Modifier.weight(1f)`. The body of `EnvEntryList` only uses `fillMaxSize` so it
does not need its own `ColumnScope`.

## Constraints (carried from the build instructions)

- No mention of AI, Claude, Anthropic or automation in any commit, PR, source comment, or
  documentation.
- No `Co-Authored-By` lines.
- Spaced hyphens (` - `) only; never em-dashes (U+2014).
- All Kotlin files end with newline.
- Compose, Decompose, coroutines and serialization are bundled in the jar. The API jar is
  `compileOnly` - the host provides it at runtime.

## Known limits, deliberately out of scope

- **No write-back.** The plugin reads environment; nothing in this plugin sets env vars or
  system properties. That would be a separate permission and a separate review.
- **No historical view beyond saved snapshots.** The 10-snapshot cap is a deliberate
  memory bound, not a feature.
- **No cross-process view.** Each BOSS process sees only its own env; this plugin does
  not try to aggregate across windows or runtimes.
- **`env_inspector_diff`'s `current` operand reads fresh state** - the diff result is
  therefore a function of when the call was made, not when the snapshots were saved. This
  matches the user's mental model ("compare what I have now to what I saved earlier") and
  is documented in the tool description.
