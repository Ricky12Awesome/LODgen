# LODgen development validation — 0.0.0

All eight configured targets build and pass packaged **client and dedicated-server startup** with their pinned Distant Horizons and C2ME versions. This pass does not run world loading, terrain generation, OpenCL, Chunky, or throughput tests. The development version remains **0.0.0**.

## Matrix results

| Minecraft | Loader | Build / unit tests | Packaged client | Packaged server | Cached software startup time |
| --- | --- | --- | --- | --- | --- |
| 1.21.1 | Fabric | PASS / 26 | PASS | PASS | 13.39 s |
| 1.21.1 | NeoForge | PASS / 26 | PASS | PASS | 15.04 s |
| 26.1.2 | Fabric | PASS / 26 | PASS | PASS | 13.27 s |
| 26.1.2 | NeoForge | PASS / 26 | PASS | PASS | 14.14 s |
| 26.2 | Fabric | PASS / 26 | PASS | PASS | 13.24 s |
| 26.2 | NeoForge | PASS / 26 | PASS | PASS | 13.56 s |
| 26.3 | Fabric | PASS / 26 | PASS | PASS | 13.47 s |
| 26.3 | NeoForge | PASS / 26 | PASS | PASS | 14.24 s |

There are **208 passing unit-test executions**, with zero failures, errors, or skipped tests, plus **16 successful runtime checks**. The table records the final pass with cached fixtures/assets and Mesa llvmpipe software rendering. A preceding pass including fixture builds took 19–25 seconds per target; initial uncached asset downloads take longer. Minecraft 1.21.1 uses Java 21; 26.x uses Java 25. Exact DH/C2ME/loader versions are in `versions.json`.

## Fixes verified

The shared config screen no longer calls `EditBox.setFilter`, which is absent on 26.x Fabric. Apply validates numbers and ranges and displays the existing validation error. Screen navigation uses Minecraft's `gui.setScreen` API on 26.2/26.3 and `setScreen` on earlier targets. On 26.x, Minecraft already extracts the screen background before calling `extractRenderState`; the addon no longer repeats that operation. The startup check reproduced the resulting “Can only blur once per frame” crash before the duplicate call was removed.

These Minecraft signature differences use the existing source preprocessor. Generation, configuration, scheduling, and tests continue sharing one implementation across targets.

## Runtime checks

The startup runner uses ordinary packaged loaders and remapped mod jars, not the development client launcher. That avoids DH's embedded Fabric API having intermediary access-widener names in a named development runtime. NeoForge client installations use the official installer; Fabric client installations use its published launcher profile. Minecraft libraries, native libraries, assets, and selected mods are cached under `build/` with artifact checksums verified where published.

The client probe waits for initial resources to load, opens the title screen and Options, presses the actual **LODgen…** button, initializes all seven config widgets, lets the config render over multiple ticks, and checks that closing it returns to Options. It also loads every generation mixin target through the runtime transformer. The final local pass uses a Linux virtual display and Mesa llvmpipe software rendering without a desktop or GPU.

The dedicated-server probe loads LODgen, DH, C2ME, and every generation mixin target. It checks that no `ServerLevel` exists and ends the isolated process before normal server initialization loads world levels. Vanilla bootstrap may create world metadata; no chunk/POI/entity region files may exist. This checks class loading and mixin compatibility, not ticket dispatch, persistence, shutdown, or terrain generation.

Every production class file in each installable jar matches the corresponding packaged startup fixture byte-for-byte. The fixture adds only test probes and its separate mixin configuration; these are excluded from the installable jars. Current results, class counts, artifact SHA-256 values, and unit-test totals are retained in [dist/validation-lodgen-0.0.0/results.json](dist/validation-lodgen-0.0.0/results.json) and [artifact-audit.json](dist/validation-lodgen-0.0.0/artifact-audit.json). Detailed logs and launcher reports remain in `build/<minecraft>/<loader>/startup/`.

Offline authentication, optional-mod accessor, and unavailable narrator warnings occur in these disposable clients; all required reports and normal client exit checks pass. No original Prism instance files are modified.

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

The startup runner accepts `--skip-build` to reuse its already-built test fixture. Build targets sequentially because Unimined shares remapping data and task history. World generation, the DH render-distance change in a live world, config Apply in multiplayer, other pack mods, and OpenCL performance are outside this pass. Earlier generation/persistence checks are described in [docs/VALIDATION-0.0.0-initial.md](docs/VALIDATION-0.0.0-initial.md); predecessor performance results remain in [docs/VALIDATION-0.2.1.md](docs/VALIDATION-0.2.1.md).
