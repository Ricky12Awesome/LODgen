# LODgen development validation — 0.0.0

All eight configured targets build and pass packaged **client and dedicated-server startup** with their pinned Distant Horizons and C2ME versions. Small Voxy-only client worlds on all three supported targets verify generation and restart persistence, using at most 80 target chunks each. A small Minecraft 1.21.1 NeoForge DH world test additionally checks custom generation distance using only 32 target chunks. This pass does not run full-radius generation, OpenCL, Chunky, or throughput tests. The development version remains **0.0.0**.

## Matrix results

| Minecraft | Loader | Build / unit tests | Packaged client | Packaged server | Cached software startup time |
| --- | --- | --- | --- | --- | --- |
| 1.21.1 | Fabric | PASS / 30 | PASS | PASS | 22.33 s |
| 1.21.1 | NeoForge | PASS / 30 | PASS | PASS | 21.3 s |
| 26.1.2 | Fabric | PASS / 30 | PASS | PASS | 18.52 s |
| 26.1.2 | NeoForge | PASS / 30 | PASS | PASS | 18.95 s |
| 26.2 | Fabric | PASS / 30 | PASS | PASS | 17.51 s |
| 26.2 | NeoForge | PASS / 30 | PASS | PASS | 17.32 s |
| 26.3 | Fabric | PASS / 30 | PASS | PASS | 16.88 s |
| 26.3 | NeoForge | PASS / 30 | PASS | PASS | 18.08 s |

There are **240 passing unit-test executions**, with zero failures, errors, or skipped tests, plus **16 successful runtime checks**. The table records the final pass with cached assets and Mesa llvmpipe software rendering, including fixture builds; initial uncached asset downloads take longer. Minecraft 1.21.1 uses Java 21; 26.x uses Java 25. Exact DH/C2ME/loader versions are in `versions.json`.

## Fixes verified

The shared config screen no longer calls `EditBox.setFilter`, which is absent on 26.x Fabric. Apply validates numbers and ranges and displays the existing validation error. Screen navigation uses Minecraft's `gui.setScreen` API on 26.2/26.3 and `setScreen` on earlier targets. On 26.x, Minecraft already extracts the screen background before calling `extractRenderState`; the addon no longer repeats that operation. The startup check reproduced the resulting “Can only blur once per frame” crash before the duplicate call was removed.

These Minecraft signature differences use the existing source preprocessor. Generation, configuration, scheduling, and tests continue sharing one implementation across targets.

## Voxy support verified

DH is now optional. LODgen bundles its own relocated NightConfig TOML parser, and a mixin plugin excludes missing renderer integrations before their targets load. DH and Voxy share the native chunk backend, admission gate and ticket cleanup through `ChunkGenerationPipeline`. Voxy alone supplies its own bounded 4×4 tile frontier; block/biome palettes and lighting are copied on the server thread before conversion workers update the client Voxy engine. Renderer shutdown drains conversion before Voxy stops saving. Completed coverage is checkpointed after engine close, and restored on reopening.

| Minecraft / loader | Pinned Voxy setup | Client, generation, shutdown | Reopen / LOD persistence | Target chunks |
| --- | --- | --- | --- | --- |
| 1.21.1 NeoForge | Roxy 0.3.3 + Voxy 0.2.16-beta (1.21.11) | PASS | PASS | 80 |
| 26.1.2 Fabric | Voxy 0.2.18-beta | PASS | PASS | 80 |
| 26.2 Fabric | Voxy 0.2.19-beta | PASS | PASS | 48 |

Each disposable client has **no DH installed**. It opens Options and renders the LODgen config, creates a small single-player world, enables the actual Voxy scheduler at radius 1, and checks completion without repeated generation. A separate 4×4 batch at chunk coordinates `(4096,-4096)` goes through the shared native path and produces a nonempty section in Voxy. On shutdown, no native chunk, POI or entity region files may exist in that distant area. A second client process reopens the world and checks that completed coverage restores and that the nonempty distant Voxy data survives. Normal player/spawn chunks retain their normal saves. The maximum target count is 80 per target (including 16 distant chunks); vanilla generates supporting and spawn chunks too.

These are real packaged clients with C2ME and Voxy's pinned dependencies from `voxy-versions.json`. Voxy ingestion/storage runs normally; LOD rendering is disabled to keep software rendering tests short. Visual LOD rendering, multiplayer generation, full-distance throughput, and OpenCL performance are outside this pass. Voxy generation intentionally runs only on single-player/LAN hosts with an integrated server. Native remote multiplayer chunks require server-side support outside this addon.

```sh
xvfb-run -a python3 scripts/voxy-test.py --world --reload
```

Roxy's published jar filename is retained in the test launcher: renaming it to `roxy.jar` collides with the `roxy` metadata module it creates in another module layer. Original download bytes are unchanged. Detailed reports and logs remain in `build/<minecraft>/<loader>/voxy-test/` and copied reports accompany the development artifacts.

## Runtime checks

The startup runner uses ordinary packaged loaders and remapped mod jars, not the development client launcher. That avoids DH's embedded Fabric API having intermediary access-widener names in a named development runtime. NeoForge client installations use the official installer; Fabric client installations use its published launcher profile. Minecraft libraries, native libraries, assets, and selected mods are cached under `build/` with artifact checksums verified where published.

The client probe waits for initial resources to load, opens the title screen and Options, presses the actual **LODgen…** button, initializes all eight config widgets, lets the config render over multiple ticks, and checks that closing it returns to Options. It also loads every generation mixin target through the runtime transformer. The final local pass uses a Linux virtual display and Mesa llvmpipe software rendering without a desktop or GPU.

The dedicated-server probe loads LODgen, DH, C2ME, and every generation mixin target. It checks that no `ServerLevel` exists and ends the isolated process before normal server initialization loads world levels. Vanilla bootstrap may create world metadata; no chunk/POI/entity region files may exist. This checks class loading and mixin compatibility, not ticket dispatch, persistence, shutdown, or terrain generation.

Every production class file in each installable jar matches the corresponding packaged startup fixture byte-for-byte. The fixture adds only test probes and its separate mixin configuration; these are excluded from the installable jars. Current results, class counts, artifact SHA-256 values, and unit-test totals are retained in [dist/validation-lodgen-0.0.0/results.json](dist/validation-lodgen-0.0.0/results.json) and [artifact-audit.json](dist/validation-lodgen-0.0.0/artifact-audit.json). Detailed logs and launcher reports remain in `build/<minecraft>/<loader>/startup/`.

Offline authentication, optional-mod accessor, and unavailable narrator warnings occur in these disposable clients; all required reports and normal client exit checks pass. No original Prism instance files are modified.

## Custom generation distance

`generationDistance = 0` preserves DH's distance. Positive values override the chunk-based FEATURES radius, in chunks. The setting is available in the in-game config and applies live: queued requests beyond the new radius are cancelled while active work drains. Ordinary player and Chunky requests keep their normal generation path.

The small 1.21.1 NeoForge packaged-server test sets custom distance 64 and DH distance 128. A 4×4 section 60 chunks from the target generates valid LOD data; a section 96 chunks away is cancelled before native generation. Applying 0 live allows the second section, and DH remains at 128. Only 32 target chunks generate, and the shutdown audit finds no chunk, POI, or entity region files for the LOD area. Dedicated-server 0 preserves client request ranges; the unit test additionally checks integrated-server 0 follows DH's radius. Whole intersecting sections and supporting native chunks can extend slightly beyond the configured boundary.

```sh
python3 scripts/integration-test.py --mc 1.21.1 --loader neoforge --quick --distance-check
```

## CI and reproduction

The GitHub workflow retains its existing build/unit-test and packaged-server integration steps. The client/server startup checks in this report were run locally and remain available through the manual command below; no headless client checks or additional workflow steps are added.

```sh
python3 scripts/build-all.py
xvfb-run -a python3 scripts/startup-test.py
```

For one target, build its regular jar first, then run:

```sh
./gradlew -PmcVersion=26.3 -Ploader=fabric build
xvfb-run -a python3 scripts/startup-test.py --mc 26.3 --loader fabric --java /path/to/java25/bin/java
```

The startup runner accepts `--skip-build` to reuse its already-built test fixture. Build targets sequentially because Unimined shares remapping data and task history. Full-radius world generation, config Apply in multiplayer, other pack mods, and OpenCL performance are outside this pass. Earlier generation/persistence checks are described in [docs/VALIDATION-0.0.0-initial.md](docs/VALIDATION-0.0.0-initial.md); predecessor performance results remain in [docs/VALIDATION-0.2.1.md](docs/VALIDATION-0.2.1.md).
