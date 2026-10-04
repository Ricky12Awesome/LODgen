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
