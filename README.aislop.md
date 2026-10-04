# LODgen

Fabric and NeoForge addon that generates chunk-based LODs for Distant Horizons `FEATURES` and Voxy through Minecraft's normal asynchronous chunk system. LOD-only terrain does not save native chunk, POI, or entity data; an optional inner radius saves ordinary chunks for pregen. C2ME and its optional OpenCL addon accelerate that pipeline automatically.

The project is in development. **The version stays at `0.0.0` until it is ready to release.** Development builds can make breaking changes without migrations or compatibility guarantees for earlier addon builds. Matching installable development jars for every target are collected in `dist/`.

## Use

Install the matching LODgen jar and either Distant Horizons or a supported Voxy installation. DH is optional. With DH, choose **FEATURES** in its chunk generator settings. LODgen follows DH's generator plan:

| DH generator plan | Automatic behavior |
| --- | --- |
| Surface Then Chunks | DH generates rough surfaces through its normal path, then its chunk phase uses LODgen, even when LODgen's Automatic generation setting is off. |
| Surface Only | DH keeps generating rough surfaces. LODgen adds chunk-based LODs only when Automatic generation is on. |
| Chunks Only | DH's chunk phase uses LODgen, even when LODgen's Automatic generation setting is off. |
| Disabled | No automatic LODgen generation, including saved-radius pregen and Voxy when DH is installed. Explicit `/lodgen` commands can still run and resume. |

Other DH chunk generator modes retain DH's own behavior. C2ME, [the C2ME OpenCL addon](https://modrinth.com/mod/qtPMklut), ScalableLux, and Chunky are optional and are never bundled.

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
caveMode = "generate"
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

Native concurrency and memory use adjust automatically; there are no batch, waiting-queue or spatial-grouping controls. Full power on a 32-thread CPU with a 32 GB heap permits 256 concurrent 4×4 requests (4,096 target chunks plus dependencies). Smaller heaps reduce that window. Automatic and command tasks complete compact 32×32 patches before moving outward, keeping native dependencies useful as the radius grows. Requests refill on completion, while conversion, ticket cleanup and normal play retain their own lifetimes. These settings control LODgen’s work; C2ME and renderer storage services retain their own worker settings. They are utilization presets, rather than strict limits on overall process CPU usage.

For maximum OpenCL throughput, use Java 25+ with a sufficiently large heap, `-XX:+UseZGC -XX:+UseCompactObjectHeaders`, ScalableLux, Lithium, FerriteCore, Structure Layout Optimizer and zFastNoise. Set C2ME’s `globalExecutorParallelism` to the available thread count or slightly below; its default can be lower. LODgen does not rewrite C2ME’s configuration or alter the scheduler used by ordinary player and Chunky requests. More RAM keeps useful native dependencies alive; allocating all RAM does not necessarily improve throughput.

`generationDistance` is a radius in chunks. **0 follows the active renderer's distance**; a positive value overrides the chunk-based FEATURES generation radius. For example, `generationDistance = 512` with DH set to 1024 limits new chunk-based LOD generation to 512 while retaining DH's 1024 render distance. Changing it in game drops waiting requests beyond the new limit; active batches finish. Whole sections overlapping the boundary are retained, and native supporting chunks may extend beyond it. With the default current-position center, DH still controls which LODs are requested. Dedicated-server current-position mode uses the explicit override too; 0 preserves client request ranges. Normal player and Chunky generation keep their ranges.

`generationCenter` accepts `"current"`, `"origin"` or `"custom"`. Current captures the player's position when the task starts, like `/lodgen start ... current`; origin uses **world spawn**, rather than coordinate 0,0; custom uses `centerX` and `centerZ`, in **blocks**. With Automatic generation on, joining a world starts a task using the configured center, generation radius and saved radius. DH and Voxy use the same task scheduler. Automatic DH surface plans run DH's normal rough generator before their native chunk jobs, while DH can continue requesting rough surfaces across its render distance. Changing the automatic center/radii can update a running or completed automatic job; paused and stopped jobs retain their state.

Chunk jobs begin at the selected center and expand through nearby compact 32×32 patches. DH's native chunk queue uses the same center-relative priority, rather than an absolute coordinate ordering that could favor the outer corner. Lowering a radius, moving the center or disabling generation stops completion callbacks from refilling the old area; admitted work finishes safely.

`savedChunkRadius` is a radius in **chunks**, default **0**. Targets inside it save as ordinary terrain; targets outside it remain LOD-only. If the saved radius exceeds the LOD radius, generation extends to the saved radius and only converts LODs inside the LOD radius. The saved area is also generated when its LODs already exist. Previously saved chunks stay saved after lowering this setting. Ordinary player and Chunky requests can still adopt terrain outside the saved radius.

**LOD caves** (`caveMode`) has three choices:

| Mode | New LOD-only terrain |
| --- | --- |
| `generate` (default) | Normal caves, carvers and underground decoration. |
| `fill` | Remove cave noise from the terrain formula before generation; skip carvers, underground structures and deep feature placements. The interior stays stone/deepslate. |
| `empty` | Also generate a hollow terrain shell directly, retaining surface layers, ocean floors, water and the terrain supporting trees and surface structures. The deeper interior starts as air. |

Fill/Empty generate in temporary native worlds with their own chunks and lighting. They do not generate caves and remove them afterward. Surface decoration remains enabled; feature placements can differ when their surrounding terrain changes. Shell thickness follows terrain density so slopes, overhangs and ocean floors remain supported. The modes currently support Overworld-style noise terrain, including vanilla, WWOO and large-biome/amplified formulas; other dimensions and unrecognized formulas retain Generate.

WWOO uses the bottom eight layers as palette markers to select surface trees and terrain. Fill retains those selectors. Empty handles their reads/writes in a separate metadata array, so those markers never become physical blocks or renderer data. Surface features are classified against the terrain floor before decoration; tall trees cannot make later surface placements look underground.

Saved-radius targets, normal player/Chunky requests and existing saved regions always use the original world and normal generation. Visiting an area whose LODs used Fill/Empty creates ordinary chunks with caves; temporary chunks cannot be adopted into the save. Changing the mode restarts active/completed automatic areas. Explicit tasks retain their selected mode across pause and restart; start a new task to change their mode. Existing LODs outside newly requested areas are not automatically cleared.

## Command tasks

Commands require operator permission level 2 and work on the integrated or dedicated server:

```text
/lodgen start <dim> <x> <z> <radius> [saved-radius]
/lodgen start <dim> <origin|current> <radius> [saved-radius]
/lodgen stop
/lodgen cancel
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

Status reports dimension, center X/Z, LOD and saved radii, completed/total chunks, percentage, active batches, throughput and any error. Autostart creates a real task, effectively running `/lodgen start` with the configured area. Pause, continue, stop and status control automatic tasks exactly like command tasks; cancel is an alias for stop. Pause stops new dispatch while active work finishes. Stop ends the task and prevents autostart or DH's queue from restarting it. One task runs per server. An explicit start can replace an automatic task; commands run even when automatic generation is off or DH's generator plan is Disabled. Automatic tasks continue to obey the automatic toggle and DH's generator plan/mode.

All task state and completion checkpoints are stored in `<world>/lodgen/task.toml`, including whether the task started automatically. A running task automatically continues on world load/server start; paused tasks stay paused and stopped/completed tasks stay finished. Orderly shutdown checkpoints outstanding work; after an abrupt crash, some uncheckpointed batches may run again. Voxy has no separate automatic frontier or tile checkpoint.

The shared task checkpoint uses layout 3. Earlier development checkpoints are not imported; start a new task to replace them.

LOD-only command work requires DH or a supported local Voxy installation. Voxy generation remains restricted to single-player/LAN hosts. Without either renderer, a saved radius at least as large as the LOD radius allows ordinary chunk pregen. All paths preserve normal player/Chunky ownership and saving.

Enable **Show chunks per second** (`showChunksPerSecond = true`) to display throughput, **generation radius in chunks**, **estimated time remaining**, and **status** above the hotbar using Minecraft’s native action bar. For example: `LODgen: 1200.0 chunks/s | Radius: 64c | ETA: 12s | Running`. It counts successfully completed LODgen target chunks over the last five seconds, including its saved pregen targets and excluding supporting chunks and normal player/Chunky work. Automatic and command tasks use their own radius, remaining targets and dimension's throughput, including when the player is in another dimension. When DH manages generation without a LODgen task, estimates use its remaining-generation counters. `/lodgen status` includes ETA too. Estimates change with throughput and show `—` while paused, waiting, stopped, or without a usable rate; completed tasks show `0s`.

The display works with Voxy and with DH when DH’s generation progress location is not **Overlay**. DH’s overlay takes priority regardless of this toggle; Chat, Log and Disabled allow LODgen’s display. **Overlay update interval (ms)** (`chunksPerSecondUpdateIntervalMs`) refreshes all action-bar fields from 1 to 60000 milliseconds, default 1000. It applies live and leaves the five-second averaging window unchanged. At zero chunks per second the message clears. It also clears when disabled, provided another message has not replaced it. Vanilla controls positioning and fading; long refresh intervals let the text fade between updates. The display respects the hidden HUD and only appears in worlds generated by the local integrated server.

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

The prior sustained-generation check copied the mods and relevant settings from the **26.1.2 Fabric** testing instance into a disposable client. It used a Ryzen 9 9950X, RTX 5070 Ti, Microsoft Java 25, a 32 GB maximum heap, ZGC, compact object headers, vanilla seed `123456789`, and full generation stages and structures. Voxy rendering remained enabled on the physical GPU, with the instance's view/simulation distance 12 and FPS limit 90. Only completed LOD target chunks counted toward throughput. Those measurements describe the build preceding the radius/ETA/status display update; the display update repeats small correctness checks.

The previous thin spiral reproduced the reported slowdown: with C2ME's default 15 workers, it reached 1,642/s and fell to **889/s** near 75 seconds. Compact automatic generation held roughly **1,300–1,400/s** later in the run with the same native worker count. The rendered 32-worker run completed **160,160 targets in 90.030 seconds**: **1,779/s overall**, **1,907/s over the final 29.648 seconds**, and a peak five-second sample of **2,128/s**. JVM CPU use averaged approximately 89% of the whole machine. **2,500/s was not reached**. This checks the reported 30–60 second decline; it does not establish a rate for an entire distance or every seed.

The copied instance's `globalExecutorParallelism = "default"` selected 15 C2ME workers. Full power permits 32 LODgen conversion workers, but C2ME's configuration controls its native workers separately. The rendered result sets `globalExecutorParallelism = 32` in the disposable test. No optimization mods are substituted: the instance's zFastNoise 1.1.0-beta.6 remains installed.

```sh
DISPLAY=:0 python3 scripts/voxy-test.py --mc 26.1.2 \
  --java /path/to/instance/java25/bin/java --instance /path/to/Prism/minecraft \
  --automatic-seconds 90 --automatic-radius 256 \
  --automatic-center-x 0 --automatic-center-z 0 --render-voxy \
  --cpu-load 5 --native-workers 32 --heap 32G --jfr --run-name rendered-32
```

The runner only changes its disposable copy, saves five-second rate/heap/queue samples, and exits with unfinished native work to exercise shutdown. Outer LOD-only regions are checked for native chunk, POI and entity files; normal player-owned chunks remain saved. The sustained report is in [docs/VALIDATION-sustained-0.0.0.md](docs/VALIDATION-sustained-0.0.0.md); earlier short 26.2 comparisons against Chunky are in [docs/VALIDATION-utilization-0.0.0.md](docs/VALIDATION-utilization-0.0.0.md). Current display checks are in [VALIDATION.md](VALIDATION.md).

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

See [development code](docs/development.md) for each subsystem's owner and
[configuration code](docs/configuration.md) for adding settings through the
shared schema and automatic screen controls.

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

Direct integration and benchmark requests honor DH's live admission limit, including when fewer native batches are available than requested workers. Integration builds include regressions for one-slot and two-slot limits, a lowered live limit, failures and draining during close. To exercise the existing CI check on a larger machine, expose two CPUs and use a fresh run directory so cached DH thread settings cannot mask the small budget:

```sh
JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 python3 scripts/integration-test.py \
  --mc 26.1.2 --loader fabric --java /path/to/jdk-25/bin/java --run-name ci-two-cpu
```

The focused suite covers TOML parsing, live admission limits, the 512-to-128 boundary, negative/world-border coordinates, shared ownership, cancellation, feature scopes, radius rounding and restart progress. The command check uses only a 5c task (100 targets), a 1c saved radius (four native saves), and four extra fixed-center DH targets:

```sh
python3 scripts/integration-test.py --mc 1.21.1 --loader neoforge --quick --task-check
```

The headless autostart check uses a 5c task and a 1c saved radius. It checks actual task creation, pause across reopening, continue, cancellation, DH queue blocking and distance shrinking:

```sh
python3 scripts/integration-test.py --mc 26.2 --loader fabric --quick --autostart-check
```

The distance regression uses DH 128/custom 64 with just two 4×4 sections (32 target chunks), including a live reset to 0:

```sh
python3 scripts/integration-test.py --mc 1.21.1 --loader neoforge --quick --distance-check
```

The DH plan regression exercises both native chunk plans with automatic generation off, rough surfaces, opt-in Surface Only chunks, center-first queue selection, mid-task disabling and re-enabling, disabled saved-radius jobs, and commands with DH disabled. Its largest automatic radius is 5c and it generates 156 native LOD targets:

```sh
python3 scripts/integration-test.py --mc 26.1.2 --loader fabric --quick --dh-plan-check
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

WWOO's disk targets now share repeated neighbor reads, check native palettes for impossible placements, and avoid unnecessary neighbor tests. Custom DH generators such as SeedGen obey fixed-task ownership of the chunk phase. The [WWOO validation](docs/VALIDATION-wwoo-0.0.0.md) records 499–567 cps versus 455–467 cps for the original disk evaluation, plus live predicate and all-target compatibility checks.

Native ore placements also reuse block readers and immutable target cursors. The [WWOO variability follow-up](docs/VALIDATION-wwoo-jitter-0.0.0.md) explains the five-second CPS window, feature workload and concurrent GC work, with allocation measurements and comparison limits.

The [LOD cave-mode validation](docs/VALIDATION-caves-0.0.0.md) records the controlled WWOO comparison: **579 CPS Generate, 668 Fill (+15%), and 729 Empty (+26%)**, with surface decoration preserved, saved chunks unchanged, and native cave checks on all eight targets.

Upstream: [Distant Horizons](https://gitlab.com/distant-horizons-team/distant-horizons), [C2ME](https://github.com/RelativityMC/C2ME-fabric), [Chunky](https://github.com/pop4959/Chunky).

LODgen bundles a relocated copy of NightConfig for TOML support without DH. Its LGPL-3.0 license is retained in `META-INF/licenses/night-config.txt`; source is available from [NightConfig](https://github.com/TheElectronWill/night-config). No renderer or generation mod is bundled.
