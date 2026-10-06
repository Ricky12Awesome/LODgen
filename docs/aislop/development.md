# Where to make changes

The production code is shared across loaders. Fabric and NeoForge entrypoints
register it; Minecraft API differences stay inside the existing source
preprocessor branches.

| Change | Owner |
| --- | --- |
| Add or change a setting | `LodgenConfig` option metadata; see [configuration](configuration.md) |
| Change automatic area selection | `AutomaticSettings` and `GenerationCenters` |
| Change command syntax or actions | `LodgenCommands` |
| Select, restore, replace, or stop a server task | `GenerationTasks` |
| Dispatch task batches, publish progress, or checkpoint | `GenerationJob` |
| Change batch admission limits | `GenerationBudget` and `BatchGate` |
| Choose normal versus temporary terrain | `ChunkGenerationPipeline` |
| Manage native generation tickets | `NormalChunkBackend` |
| Change save ownership rules | `SavePolicy`; Minecraft adapters use `PersistenceRegistry` and `ChunkPersistence` |
| Convert native chunks to DH data | `DhChunkConversion` |
| Aggregate and write task output to DH | `DhTaskSink` |
| Handle DH queue output and its pooled destination | `FeatureGenerationService` |
| Register or dispatch renderer output | `RendererSinks` |
| Manage Voxy sessions and shutdown | `VoxyGeneration`; reflection and ingestion live in `VoxyBridge` |
| Transform cave density formulas | `CaveDensity`; Minecraft codec differences live in `LodTerrainSettings` |
| Create or dispose of temporary terrain worlds | `LodGenerationWorld` |
| Resolve dimension identifiers and storage directories | `WorldPaths` |
| Parse or atomically replace TOML files | `TomlFiles` |
| Prepare Minecraft clients and downloads for checks | `scripts/minecraft_launcher.py` |
| Read script configuration and artifact version | `scripts/script_utils.py` |

Generation flows from a task or DH request through `ChunkGenerationPipeline`.
The pipeline admits work, chooses its terrain source, requests native chunks,
converts their output, and waits for native ticket release before reporting
completion. Renderer adapters own their data format and resource cleanup.

Keep completion callbacks on the server thread for task progress and checkpoint
changes. Closing a pipeline stops new dispatch while active work drains. A
renderer startup exception becomes a failed future through `Futures.attempt`,
so other outputs still finish before the batch releases its resources.

The script entrypoints retain their existing flags and isolated test
directories. Startup and Voxy checks import the shared client launcher instead
of importing another executable script.

Use `python3 scripts/build-all.py` for the eight production targets. Runtime
checks are separate: `scripts/startup-test.py` checks packaged menus/loaders,
`scripts/integration-test.py` checks generation and persistence, and
`scripts/voxy-test.py` checks Voxy without DH. Run target builds sequentially
because Unimined shares remapping state.

## Client hot reload

Run `./gradlew runClient` from the project checkout you are editing. Java and
resource edits are watched automatically, including the original Minecraft
sources before preprocessing. The console prints `[LODgen hot reload]` when
changes compile or reload. No debugger or second Gradle watcher is needed.

Java compilation runs in a staging directory, then updates the running JVM and
its class files. Compilation errors keep the last working code active; saving a
correction retries the reload. The installed OpenJDK supports method-body edits.
Adding or removing fields or methods, changing signatures, deleting classes,
and changing Mixin targets or configuration require restarting the client.
Existing object state and static initializers are retained during a reload.

Client assets, including language files and textures, are copied automatically
and trigger Minecraft's resource reload. Data-pack resources are copied too,
but still require the server's `/reload` command where applicable. Mod metadata,
Mixin configuration, dependencies, and Gradle changes require a restart.

Use `./gradlew runClient -PhotReload=false` to disable the watcher. It is also
disabled for `selfTest` and `startupTest` runs. The watcher and Mixin Java agents
are development tools and are not included in the distributable mod jar.

## Command messages

Edit `src/common/resources/assets/lodgen/lang/en_us.json` keys under
`lodgen.command.*` to change command feedback and errors. The status templates
use positional placeholders so translations can reorder values:

- `lodgen.command.status.task` has 17 arguments: automaticPrefix, state,
  dimension, X, Z, radiusChunks, radiusBlocks, savedChunks, savedBlocks,
  caveMode, completed, total, percent, active, rate, ETA, errorSuffix.
- `lodgen.command.status.noTask` has 11 arguments: enabled, state, dimension,
  X, Z, centerMode, radius, savedRadius, completed, rate, ETA.

Both `%s` and indexed `%1$s` placeholders work; `%%` produces a literal `%`.
Use `\u00A7a` (or `§a`) for color and `§r` to reset it. Formatting codes
continue across placeholder values. State, cave mode and error text use
translatable entries too. Keep colors in language resources, not Java code.

ETA units and wording are controlled by `lodgen.command.duration.unknown`,
`.seconds`, `.minutes`, `.hours`, and `.days`. The values receive respectively
no arguments, seconds, minutes and seconds, hours and minutes, or days and
hours; localize their singular/plural forms as appropriate.

Command responses remain translation components until the client renders them,
so dedicated-server clients use their own language. The English language
resource also supplies the server-console fallback. Restart the client once
after adding the formatting mixin; later language-file edits reload with the
client resource watcher.
