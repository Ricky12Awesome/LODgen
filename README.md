# LODgen

Fabric and NeoForge addon that sends Distant Horizons `FEATURES` requests through Minecraft's normal asynchronous chunk system, without saving chunk, POI, or entity data for terrain loaded solely for DH. C2ME and its optional OpenCL addon accelerate that pipeline automatically.

The project is in development. **The version stays at `0.0.0` until it is ready to release.** Development builds can make breaking changes without migrations or compatibility guarantees for earlier addon builds. The current installable development build is `dist/lodgen-1.21.1-neoforge-0.0.0.jar`.

## Use

Install Distant Horizons and the matching LODgen jar. Choose **FEATURES** in DH's chunk generator settings and enable a generator plan that includes chunks. C2ME, [the C2ME OpenCL addon](https://modrinth.com/mod/qtPMklut), ScalableLux, and Chunky are optional and are never bundled.

Open **Options → LODgen…** in game. On NeoForge, **Mods → LODgen → Config** opens the same screen. Adjust enabled generation, active batches, waiting batches, and grouping of nearby requests, then click **Apply**. **Cancel** discards edits; **Defaults** restores the draft defaults. Changes apply immediately to local generation, including the integrated server. Running native work drains safely when the limit decreases or the addon is disabled.

Settings are stored in `config/lodgen.toml`:

```toml
enabled = true
pipelineBatches = 32
queuedBatches = 64
spatialBatching = true
```

`pipelineBatches` accepts 1–64 active batches per dimension; 32 default-size DH requests provide 512 target chunks plus their native dependencies. `queuedBatches` accepts 0–1024 waiting requests, which do not occupy waiting workers. Larger windows use more memory. `spatialBatching` groups nearby requests within DH's distance/detail priority bands. DH's thread count and C2ME's worker count also affect parallelism.

LODgen reads only `config/lodgen.toml` and creates it with defaults if missing. Manual file edits are read at startup. In multiplayer, this screen changes the local installation's settings; a dedicated server uses its own TOML file.

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

**This update is built and validated only for 1.21.1 NeoForge**, as requested. Other targets remain unverified for this change. OpenCL requires Java 25, including on Minecraft 1.21.1. Exact dependencies are recorded in `versions.json` and `test-versions.json`.

Use JDK 25 for Gradle with the JDK 21 compilation toolchain installed:

```sh
./gradlew -PmcVersion=1.21.1 -Ploader=neoforge build
```

Install the regular jar from `build/1.21.1/neoforge/libs/`, not the `-dev.jar` or self-test jar. Run target builds sequentially because Unimined shares remapping files and task history. `scripts/build-all.py` retains the full matrix workflow; the development version remains `0.0.0`.

## Quick checks

The focused suite covers TOML parsing, live admission limits, the 512-to-128 boundary, negative/world-border coordinates, shared ownership, cancellation, and feature scopes. A small packaged-server check covers the actual DH queue, LOD storage, concurrent Chunky, lighting, no-save behavior, and restart persistence:

```sh
python3 scripts/integration-test.py --mc 1.21.1 --loader neoforge --quick \
  --opencl --chunky --benchmark 8 --dh-queue --dh-executor --store-lods \
  --native-workers 15 --chunky-working-count 768 \
  --worldgen-instance '/path/to/Prism/instance/minecraft'
```

`--quick` skips benchmark warmup, caps each server run, and uses the existing versioned server installation. `--skip-build` reuses the self-test jar. The tiny workload is a correctness check, not a performance benchmark. For an enabled addon with `--dh-queue`, quick checks temporarily apply one active batch and zero waiting slots, then restore the original settings to verify live backpressure. Only the selected WWOO/Continents worldgen jars and configuration are copied. The runner accepts the EULA for a disposable server and recreates only its world under `build/`.

See [VALIDATION.md](VALIDATION.md) for current checks. Earlier performance measurements belong to the predecessor and are preserved in [docs/VALIDATION-0.2.1.md](docs/VALIDATION-0.2.1.md); they are not a new benchmark of this development build.

Upstream: [Distant Horizons](https://gitlab.com/distant-horizons-team/distant-horizons), [C2ME](https://github.com/RelativityMC/C2ME-fabric), [Chunky](https://github.com/pop4959/Chunky).
