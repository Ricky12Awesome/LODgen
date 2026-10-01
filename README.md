# LODgen

Fabric and NeoForge addon that generates chunk-based LODs for Distant Horizons `FEATURES` and Voxy through Minecraft's normal asynchronous chunk system, without saving chunk, POI, or entity data for terrain loaded solely for LODs. C2ME and its optional OpenCL addon accelerate that pipeline automatically.

The project is in development. **The version stays at `0.0.0` until it is ready to release.** Development builds can make breaking changes without migrations or compatibility guarantees for earlier addon builds. Matching installable development jars for every target are collected in `dist/`.

## Use

Install the matching LODgen jar and either Distant Horizons or a supported Voxy installation. DH is optional. With DH, choose **FEATURES** in its chunk generator settings and enable a generator plan that includes chunks. C2ME, [the C2ME OpenCL addon](https://modrinth.com/mod/qtPMklut), ScalableLux, and Chunky are optional and are never bundled.

Open **Options → LODgen…** in game. On Fabric with Mod Menu installed, **Mods → LODgen → Configure** opens the same screen. On NeoForge, **Mods → LODgen → Config** opens it too. Adjust enabled generation, active batches, waiting batches, generation distance, and grouping of nearby requests, then click **Apply**. **Cancel** discards edits; **Defaults** restores the draft defaults. Changes apply immediately to local generation, including the integrated server. Running native work drains safely when the limit decreases or the addon is disabled.

Settings are stored in `config/lodgen.toml`:

```toml
enabled = true
pipelineBatches = 32
queuedBatches = 64
spatialBatching = true
generationDistance = 0
```

`pipelineBatches` accepts 1–64 active batches per dimension; 32 default-size DH requests provide 512 target chunks plus their native dependencies. `queuedBatches` accepts 0–1024 waiting requests, which do not occupy waiting workers. Larger windows use more memory. `spatialBatching` groups nearby requests within DH's distance/detail priority bands. DH's thread count and C2ME's worker count also affect parallelism.

`generationDistance` is a radius in chunks. **0 follows the active renderer's distance**; a positive value overrides the chunk-based FEATURES generation radius. For example, `generationDistance = 512` with DH set to 1024 limits new chunk-based LOD generation to 512 while retaining DH's 1024 render distance. Changing it in game drops waiting requests beyond the new limit; active batches finish. Whole sections overlapping the boundary are retained, and native supporting chunks may extend beyond it. DH still controls which LODs are requested. Dedicated servers use the explicit override too; 0 preserves their existing request ranges. This only affects LODgen's FEATURES queue; normal player and Chunky generation keep their ranges.

LODgen reads only `config/lodgen.toml` and creates it with defaults if missing. Manual file edits are read at startup. In multiplayer, this screen changes the local installation's settings; a dedicated server uses its own TOML file.

## Voxy

Voxy has no generation queue, so LODgen supplies one for single-player and LAN hosts. It generates nearby 4×4 chunk tiles through the same normal asynchronous pipeline used for DH, allowing C2ME/OpenCL to replace vanilla generation. Block, biome and lighting snapshots are converted into Voxy data without saving LOD-only native chunks. Ordinary player and Chunky requests still adopt chunks normally.

| Minecraft | Loader | Tested Voxy setup |
| --- | --- | --- |
| 1.21.1 | NeoForge | [Roxy](https://modrinth.com/mod/roxy) + [Voxy 0.2.16-beta for 1.21.11](https://modrinth.com/mod/voxy/version/H3w2nVdU) |
| 26.1.2 | Fabric | [Voxy 0.2.18-beta](https://modrinth.com/mod/voxy/version/Zt3LPI0b) |
| 26.2 | Fabric | [Voxy 0.2.19-beta](https://modrinth.com/mod/voxy/version/LzyXnE51) |

Install Voxy's required Sodium/Fabric API dependencies, and Roxy's dependencies where applicable. These mods are not bundled. Voxy support is disabled on other LODgen targets.

**Options → LODgen… → Generation distance** controls both integrations. With Voxy, `0` follows its render distance (`section_render_distance × 32` chunks); a positive value sets a custom radius. Voxy's ingestion and LODgen's **Enabled** setting must both be on. Movement, dimension changes and lowering the distance update the frontier immediately; existing native work drains. Generation uses square bounds, retains whole boundary tiles, and can require supporting chunks beyond the radius.

Completed tiles are tracked per Voxy world/dimension and checkpointed under Voxy's storage path in `lodgen/<world-id>.tiles` after the engine closes. Reopening a world restores that coverage; deleting the entire Voxy cache resets it. A crash before a checkpoint can cause some tiles to regenerate. Voxy still saves its own LOD database. LODgen never changes Voxy's render distance or forces distant chunks into a remote server: multiplayer clients can only ingest terrain sent by that server.

To run the small disposable Voxy-only client checks (no DH, at most 80 target chunks per target):

```sh
xvfb-run -a python3 scripts/voxy-test.py --world --reload
```

## Render-distance changes

The integrated server checks waiting FEATURES requests against the current DH render distance before dispatch. Lowering the radius from 512 to 128 drops stale requests outside the smaller render area, even when they were queued for the old distance. Already running generation finishes and releases its tickets; it does not keep dispatching the old frontier. Edge sections overlapping the visible area are retained. Moving or expanding the distance can request terrain again normally.

Dedicated-server and Chunky generation retain their own ranges. Custom API generators and other DH generator modes retain their existing behavior.

## Generation and persistence

LODgen submits reference-counted loading tickets and native `FULL` chunk requests through the same chunk holders used by normal Minecraft generation and Chunky. C2ME owns scheduling, dependency sharing, and OpenCL batching. FEATURES controls persistence: DH-only chunks stay transient while DH still writes its LOD database.

Native block and sky lighting are copied for DH conversion. Transient chunk events bypass DH's ordinary update queue because the explicit FEATURES request already converts them. Nearby requests share supporting terrain and native holders.

Chunks already loaded normally remain saveable. Player requests, Chunky, forced tickets, and other normal requests permanently adopt their dependency footprint, including cache hits. Dirty flags are preserved so later player edits save normally. Synchronous feature/structure lookups inherit transient ownership; village cat structure lookups cannot accidentally adopt DH-only terrain.

Write guards cover vanilla and C2ME chunk serialization, POI/entity storage, unload, and shutdown. Read guards avoid creating empty region files. Ownership masks remain until the level closes to protect against delayed writes. Native work holds tickets until pooled LOD conversion is finished.

DH's public generator override API has no public delegation to its builtin generator, so mixins select only the builtin block-detail FEATURES path. DH is pinned because LOD conversion uses its internal builder.

## Targets and building

Sources and build setup support both loaders with shared code:

| Minecraft | Minimum Java | Loaders | Required DH |
| --- | --- | --- | --- |
| 1.21.1 | 21 | Fabric, NeoForge | 3.3.3 |
| 26.1.2 | 25 | Fabric, NeoForge | 3.3.3 |
| 26.2 | 25 | Fabric, NeoForge | 3.3.3 |
| 26.3 | 25 | Fabric, NeoForge | 3.3.4 |

Every target is built and checked for packaged client/server startup with DH and C2ME. The client check opens and renders the title screen, Options, and LODgen's config screen; the server check transforms every generation mixin target before loading world levels. World generation tests are separate. OpenCL requires Java 25, including on Minecraft 1.21.1. Exact dependencies are recorded in `versions.json` and `test-versions.json`.

Use JDK 25 for Gradle with the JDK 21 compilation toolchain installed:

```sh
./gradlew -PmcVersion=1.21.1 -Ploader=neoforge build
```

Install the regular jar from the target's `libs/` directory, not the `-dev.jar`, self-test jar, or startup fixture. Run target builds sequentially because Unimined shares remapping files and task history. `scripts/build-all.py` builds/tests the full matrix and collects the regular jars; the development version remains `0.0.0`.

## Quick checks

Build all targets, then check packaged startup without generating terrain:

```sh
python3 scripts/build-all.py
xvfb-run -a python3 scripts/startup-test.py
```

The Linux startup runner uses JDK 21 for 1.21.1 and JDK 25 for 26.x. It accepts `--mc`, `--loader`, `--java`, and `--skip-build` to check a single target or reuse its startup fixture. Xvfb and Mesa allow CI to render menus without a physical display. Official client assets and loader installations are cached under `build/`; first runs download them. Reports/logs stay under `build/<minecraft>/<loader>/startup/`. Production class files must match the packaged fixture byte-for-byte, and test probes must be absent from the regular jar. These startup checks are available locally; the GitHub workflow keeps its existing build and packaged-server integration steps.

The focused suite covers TOML parsing, live admission limits, the 512-to-128 boundary, negative/world-border coordinates, shared ownership, cancellation, and feature scopes. The distance regression uses DH 128/custom 64 with just two 4×4 sections (32 target chunks), including a live reset to 0:

```sh
python3 scripts/integration-test.py --mc 1.21.1 --loader neoforge --quick --distance-check
```

A small packaged-server check covers the actual DH queue, LOD storage, concurrent Chunky, lighting, no-save behavior, and restart persistence:

```sh
python3 scripts/integration-test.py --mc 1.21.1 --loader neoforge --quick \
  --opencl --chunky --benchmark 8 --dh-queue --dh-executor --store-lods \
  --native-workers 15 --chunky-working-count 768 \
  --worldgen-instance '/path/to/Prism/instance/minecraft'
```

`--quick` skips benchmark warmup, caps each server run, and uses the existing versioned server installation. `--skip-build` reuses the self-test jar. The tiny workload is a correctness check, not a performance benchmark. For an enabled addon with `--dh-queue`, quick checks temporarily apply one active batch and zero waiting slots, then restore the original settings to verify live backpressure. Only the selected WWOO/Continents worldgen jars and configuration are copied. The runner accepts the EULA for a disposable server and recreates only its world under `build/`.

See [VALIDATION.md](VALIDATION.md) for current checks. Earlier performance measurements belong to the predecessor and are preserved in [docs/VALIDATION-0.2.1.md](docs/VALIDATION-0.2.1.md); they are not a new benchmark of this development build.

Upstream: [Distant Horizons](https://gitlab.com/distant-horizons-team/distant-horizons), [C2ME](https://github.com/RelativityMC/C2ME-fabric), [Chunky](https://github.com/pop4959/Chunky).

LODgen bundles a relocated copy of NightConfig for TOML support without DH. Its LGPL-3.0 license is retained in `META-INF/licenses/night-config.txt`; source is available from [NightConfig](https://github.com/TheElectronWill/night-config). No renderer or generation mod is bundled.
