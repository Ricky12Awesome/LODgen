# LODgen

Fabric and NeoForge addon that generates chunk-based LODs for Distant Horizons `FEATURES` and Voxy through Minecraft's normal asynchronous chunk system. LOD-only terrain does not save native chunk, POI, or entity data; an optional inner radius saves ordinary chunks for pregen. C2ME and its optional OpenCL addon accelerate that pipeline automatically.

The project is in development. **The version stays at `0.0.0` until it is ready to release.** Development builds can make breaking changes without migrations or compatibility guarantees for earlier addon builds. Matching installable development jars for every target are collected in `dist/`.

## Use

Install the matching LODgen jar and either Distant Horizons or a supported Voxy installation. DH is optional. With DH, choose **FEATURES** in its chunk generator settings and enable a generator plan that includes chunks. C2ME, [the C2ME OpenCL addon](https://modrinth.com/mod/qtPMklut), ScalableLux, and Chunky are optional and are never bundled.

Open **Options → LODgen…** in game. On Fabric with Mod Menu installed, **Mods → LODgen → Configure** opens the same screen. On NeoForge, **Mods → LODgen → Config** opens it too. All settings appear in one list from top to bottom, including generation, center, saving, CPU load and action-bar options. Scroll down to reach lower settings on smaller windows; **Apply**, **Cancel** and **Defaults** stay visible at the bottom. **Apply** saves the draft, **Cancel** discards it, and **Defaults** resets every option. Unsaved edits and scroll position survive resizing. Changes apply immediately to automatic local generation, including the integrated server. Running native work drains safely when the limit decreases or the addon is disabled.

Settings are stored in `config/lodgen.toml`:

```toml
enabled = true
cpuLoad = 3
generationDistance = 0
generationCenter = "current"
centerX = 0
centerZ = 0
savedChunkRadius = 0
showChunksPerSecond = false
chunksPerSecondUpdateIntervalMs = 1000
```

`cpuLoad` controls Voxy utilization. With DH installed, LODgen follows **DH’s CPU Load** (including its advanced thread count and runtime ratio); the Voxy control is disabled. Both changes apply live. The five Voxy levels match DH’s processor scaling:

| Value | CPU load | Conversion workers | Duty ratio |
| --- | --- | --- | --- |
| 1 | Minimal impact | 10% of available processors, rounded up | 50% |
| 2 | Low impact | 25% | 100% |
| 3 | Balanced (default) | 50% | 100% |
| 4 | Aggressive | 75% | 100% |
| 5 | Full power | 100% | 100% |

Native concurrency and memory use adjust automatically; there are no batch, waiting-queue or spatial-grouping controls. Full power on a 32-thread CPU with a 32 GB heap permits 256 concurrent 4×4 requests (4,096 target chunks plus dependencies). Smaller heaps reduce that window. Requests are grouped spatially and refilled on completion, while conversion, ticket cleanup and normal play retain their own lifetimes. These settings control LODgen’s work; C2ME and renderer storage services retain their own worker settings. They are utilization presets, rather than strict limits on overall process CPU usage.

For maximum OpenCL throughput, use Java 25+ with a sufficiently large heap, `-XX:+UseZGC -XX:+UseCompactObjectHeaders`, ScalableLux, Lithium, FerriteCore, Structure Layout Optimizer and zFastNoise. Set C2ME’s `globalExecutorParallelism` to the available thread count or slightly below; its default can be lower. LODgen does not rewrite C2ME’s configuration or alter the scheduler used by ordinary player and Chunky requests. More RAM keeps useful native dependencies alive; allocating all RAM does not necessarily improve throughput.

`generationDistance` is a radius in chunks. **0 follows the active renderer's distance**; a positive value overrides the chunk-based FEATURES generation radius. For example, `generationDistance = 512` with DH set to 1024 limits new chunk-based LOD generation to 512 while retaining DH's 1024 render distance. Changing it in game drops waiting requests beyond the new limit; active batches finish. Whole sections overlapping the boundary are retained, and native supporting chunks may extend beyond it. With the default current-position center, DH still controls which LODs are requested. Dedicated-server current-position mode uses the explicit override too; 0 preserves client request ranges. Normal player and Chunky generation keep their ranges.

`generationCenter` accepts `"current"`, `"origin"` or `"custom"`. Current follows the player; origin uses **world spawn**, rather than coordinate 0,0; custom uses `centerX` and `centerZ`, in **blocks**. Fixed-center DH FEATURES generation runs independently of the player's viewport, and Voxy uses the selected center for its own frontier.

`savedChunkRadius` is a radius in **chunks**, default **0**. Targets inside it save as ordinary terrain; targets outside it remain LOD-only. If the saved radius exceeds the LOD radius, generation extends to the saved radius and only converts LODs inside the LOD radius. The saved area is also generated when its LODs already exist. Previously saved chunks stay saved after lowering this setting. Ordinary player and Chunky requests can still adopt terrain outside the saved radius.

## Command tasks

Commands require operator permission level 2 and work on the integrated or dedicated server:

```text
/lodgen start <dim> <x> <z> <radius> [saved-radius]
/lodgen start <dim> <origin|current> <radius> [saved-radius]
/lodgen stop
/lodgen pause
/lodgen continue
/lodgen status
```

Dimensions include `overworld`, `the_nether`, `the_end` and custom namespaced dimensions. X/Z are horizontal **block coordinates**. `origin` resolves the selected dimension's world spawn; `current` uses the player and requires that player to be in the selected dimension. A command center remains fixed after starting, even if the player moves. Voxy can generate another dimension without visiting it.

Radii are block counts by default, or chunk counts with a `c` suffix. Block radii round up to whole chunks: `4096` = `256c`, `8190` = `512c`, and `2040` = `128c`. The radius must be positive; omitted `saved-radius` defaults to **0**, independently of the automatic config. Shapes are always square. A radius of 64c covers a 128×128 chunk square; native supporting terrain can extend outside it.

For a smaller example:

```text
/lodgen start overworld current 64c 16c
/lodgen status
/lodgen pause
/lodgen continue
```

Status reports dimension, center X/Z, LOD and saved radii, completed/total chunks, percentage, active batches, throughput and any error. Pause stops new dispatch while active work finishes. Continue resumes a paused task; stop ends it and permits another start. One command task runs per server. An active or paused command takes priority over automatic generation in its dimension. Explicit commands run even when automatic generation's **Enabled** setting is off.

Task state and completion checkpoints are stored in `<world>/lodgen/task.toml`. A running task automatically continues on world load/server start; paused tasks stay paused and stopped/completed tasks stay finished. Orderly shutdown checkpoints outstanding work; after an abrupt crash, some uncheckpointed batches may run again. Fixed-center automatic generation and automatic saved areas have separate dimension checkpoints in the same folder.

LOD-only command work requires DH or a supported local Voxy installation. Voxy generation remains restricted to single-player/LAN hosts. Without either renderer, a saved radius at least as large as the LOD radius allows ordinary chunk pregen. All paths preserve normal player/Chunky ownership and saving.

Enable **Show chunks per second** (`showChunksPerSecond = true`) to display LODgen throughput above the hotbar using Minecraft’s native action bar, just like DH. It counts successfully completed LODgen target chunks over the last five seconds, including its saved pregen targets and excluding supporting chunks and normal player/Chunky work. It works with Voxy and with DH when DH’s generation progress location is not **Overlay**. DH’s overlay takes priority regardless of this toggle; Chat, Log and Disabled allow LODgen’s display. **Overlay update interval (ms)** (`chunksPerSecondUpdateIntervalMs`) controls refresh frequency from 1 to 60000 milliseconds, default 1000 (one second). It applies live and leaves the five-second averaging window unchanged. At zero chunks per second the message clears. It also clears when disabled, provided another message has not replaced it. Vanilla controls positioning and fading; long refresh intervals let the text fade between updates. The display respects the hidden HUD and only appears in worlds generated by the local integrated server.

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

To run the small disposable Voxy-only client checks (no DH, at most 88 target chunks per target):

```sh
xvfb-run -a python3 scripts/voxy-test.py --world --reload
```

## Performance checks

The local 26.2 Fabric benchmark uses the Ryzen 9 9950X and RTX 5070 Ti, a 32 GB heap, vanilla seed `123456789`, all generation stages and structures, and the requested optimization mods. Voxy LOD rendering is disabled for the virtual-display test; real voxel conversion and storage remain enabled. Warmup and measured squares each contain 16,384 targets (64c radius). No worldgen shortcuts or supporting chunks are counted as completed targets.

```sh
xvfb-run -a python3 scripts/voxy-test.py --mc 26.2 --benchmark 64 \
  --cpu-load 5 --native-workers 32 --heap 32G --jfr --chunky-native-only

python3 scripts/integration-test.py --mc 26.2 --loader fabric \
  --java /usr/lib/jvm/java-25-openjdk/bin/java --run-name performance-check \
  --opencl --chunky --optimized --heap 32G --benchmark 128 --warmup-axis 128 \
  --workers 256 --dh-threads 32 --native-workers 32 --chunky-working-count 768 \
  --dh-queue --dh-executor --store-lods --jfr
```

The benchmark pins zFastNoise 1.0.40, whose surface optimizations complement OpenCL; 1.1.1 omits those optimizations. With 32 C2ME workers, Voxy measured **1,802 completed LOD chunks/s**, compared with **1,836 native Chunky chunks/s** with Voxy ingestion disabled for that baseline. With 24 C2ME workers, Voxy measured **1,873/s** versus **1,827/s** native Chunky. DH’s actual queue with database updates measured **1,594/s**. Overall system CPU utilization was around 95% in the profiled 32-worker Voxy/native comparison. **2,500/s was not reproduced**, including by the native-only baseline. These short-area results depend on terrain, warmup, background load and renderer work; they do not establish a sustained rate across every world or modpack. See [VALIDATION.md](VALIDATION.md) for logs and checks.

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

The focused suite covers TOML parsing, live admission limits, the 512-to-128 boundary, negative/world-border coordinates, shared ownership, cancellation, feature scopes, radius rounding and restart progress. The command check uses only a 5c task (100 targets), a 1c saved radius (four native saves), and four extra fixed-center DH targets:

```sh
python3 scripts/integration-test.py --mc 1.21.1 --loader neoforge --quick --task-check
```

The distance regression uses DH 128/custom 64 with just two 4×4 sections (32 target chunks), including a live reset to 0:

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

`--quick` skips benchmark warmup, caps each server run, and uses the existing versioned server installation. `--skip-build` reuses the self-test jar. The tiny workload is a correctness check, not a performance benchmark. For an enabled addon with `--dh-queue`, quick checks temporarily set DH to one worker, then restore its original CPU load to verify live backpressure. Only the selected WWOO/Continents worldgen jars and configuration are copied. The runner accepts the EULA for a disposable server and recreates only its world under `build/`.

See [VALIDATION.md](VALIDATION.md) for current checks. The 26.2 vanilla/OpenCL measurements include generation and real renderer conversion; the 2,500 chunks/s expectation is not reached in every test area. Earlier performance measurements belong to the predecessor and are preserved in [docs/VALIDATION-0.2.1.md](docs/VALIDATION-0.2.1.md); they are not a new benchmark of this development build.

Upstream: [Distant Horizons](https://gitlab.com/distant-horizons-team/distant-horizons), [C2ME](https://github.com/RelativityMC/C2ME-fabric), [Chunky](https://github.com/pop4959/Chunky).

LODgen bundles a relocated copy of NightConfig for TOML support without DH. Its LGPL-3.0 license is retained in `META-INF/licenses/night-config.txt`; source is available from [NightConfig](https://github.com/TheElectronWill/night-config). No renderer or generation mod is bundled.
