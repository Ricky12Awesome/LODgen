# LODgen development validation — 0.0.0

This update addresses automatic Voxy generation slowing after 30–60 seconds and repeated `Unloaded chunk` warnings when leaving a world early. The version remains **0.0.0**. The GitHub workflow retains its existing steps.

## Diagnosis

The supplied [Spark profile](https://spark.lucko.me/gYt7M0H4Xy) is from **26.1.2 Fabric**, rather than the previous 26.2 benchmark. Its C2ME native pool has 15 workers, with surface generation prominent in the worker stacks. LODgen conversion workers mostly wait for native chunks. The testing instance's `globalExecutorParallelism = "default"` selects those 15 workers even though LODgen's full-power conversion pool permits 32.

The automatic queue traversed a widening, thin spiral of 4×4 requests. Farther rings share fewer nearby generation dependencies. A 75-second reproduction with the actual instance mods reached approximately 1,642/s, then fell to 889/s. The earlier nine-second measured benchmark did not exercise this decline.

Automatic generation now finishes compact **32×32 patches** before advancing to nearby patches. Individual tiles, saved-radius rules and persisted coverage keys remain unchanged. Generation can reuse existing native dependencies as the frontier grows. Enumeration retains a fixed per-tick scan budget, exact existing tile-overlap bounds, negative-coordinate support and world-edge checks.

Shutdown previously left the Voxy session open while the integrated server unloaded chunks. Failing FULL futures logged warnings and refilled generation during teardown. An integrated-server HEAD hook now checkpoints/stops command tasks and closes automatic Voxy generation before native chunk unloading. The session closes before waiting for current conversion readers, prevents new sessions on the stopping server, and retains successfully converted coverage until Voxy closes its engine. Expected shutdown cancellations do not retry or emit warning stacks. The base-server task shutdown hook is idempotent.

## Sustained automatic generation

Hardware: Ryzen 9 9950X, 32 logical CPUs; RTX 5070 Ti, 16 GB VRAM; 62 GiB system RAM; NVIDIA driver 615.71.09. Other desktop applications were left running. Every run uses a **32 GiB maximum heap**, Java 25, `-XX:+UseZGC -XX:+UseCompactObjectHeaders`, vanilla seed `123456789`, all generation stages and vanilla structures. Only completed LOD target chunks count toward the rate; supporting chunks are excluded. The timer starts with automatic generation, including its warmup.

The runner copies the testing instance's mods, C2ME/Voxy configuration and Minecraft options into disposable directories. Its actual stack includes C2ME/OpenCL alpha.0.62, Voxy 0.2.18, ScalableLux, Lithium, FerriteCore, Structure Layout Optimizer and zFastNoise **1.1.0-beta.6+26.1**. No optimization mod was replaced, and no 26.2-only zFastNoise jar was installed in 26.1.2.

| Automatic order | C2ME workers | Voxy rendering | Duration | Targets | Overall targets/s | Final approximately 30 seconds |
| --- | --- | --- | --- | --- | --- | --- |
| Previous spiral, control | 15 (instance default) | Off | 75.024 s | 95,984 | 1,279 | 1,068/s |
| Compact patches | 15 (instance default) | Off | 75.027 s | 103,872 | 1,384 | 1,349/s |
| Compact patches | 24 | Off | 90.025 s | 150,720 | 1,674 | 1,702/s |
| Compact patches | 32 | **On, physical NVIDIA GPU** | 90.030 s | 160,160 | **1,779** | **1,907/s** |

The first three comparisons use an isolated far center, Xvfb/Mesa at 30 FPS and disabled LOD rendering; voxel conversion and storage remain active. The final run uses the instance's Microsoft Java **25.0.1**, the native desktop display, Voxy rendering/ingestion enabled, center **0,0**, radius **256c**, Minecraft view/simulation distance **12**, FPS limit **90**, and Voxy service threads **10**. C2ME's worker override is applied only to each disposable copy by the test runner.

The final run's last period spans **29.648 seconds**. Its last five-second samples are 1,904/s, 1,862/s, 2,128/s and 2,019/s. It keeps 256 batches active, records zero retry tiles, and remains faster at the end than at 30 seconds. JVM CPU utilization averages **89.4% of the whole machine** over 89 one-second JFR samples; system CPU averages 98.7%. The maximum observed sampled heap use is approximately 19.9 GiB, within the 32 GiB limit.

**2,500/s was not reproduced.** These measurements test the reported first-minute decline; they do not establish an entire-distance throughput guarantee. Seeds, terrain, JVM warmup and background load differ between phases. The 15-worker controls isolate the compact-order change; the rendered 32-worker run additionally changes the native worker count, center, Java distribution and rendering path.

Each run deliberately exits with **256 unfinished batches**. The complete log is checked for both `Voxy generation failed` and `Unloaded chunk`. All four checks pass without either warning. Shutdown audits find no native chunk/POI/entity region files in the isolated LOD-only areas. Near-origin player/spawn regions are allowed to save normally; outer LOD-only regions in the rendered run contain no native files. The final rendered fixture matches all current production class bytes.

Evidence: `dist/validation-lodgen-0.0.0/performance/` contains result JSON with five-second rate/heap/queue samples, CPU summaries and launch logs. Original JFR recordings remain in `build/26.1.2/fabric/voxy-automatic-<run-name>/client/automatic.jfr`.

```sh
DISPLAY=:0 python3 scripts/voxy-test.py --mc 26.1.2 \
  --java /path/to/instance/java25/bin/java --instance /path/to/Prism/minecraft \
  --automatic-seconds 90 --automatic-radius 256 \
  --automatic-center-x 0 --automatic-center-z 0 --render-voxy \
  --cpu-load 5 --native-workers 32 --heap 32G --jfr --run-name rendered-32
```

Use `--native-workers 0` to retain the source instance's C2ME configuration. Omit `--render-voxy` for a virtual-display check; real ingestion remains active. The performance runner never edits the original Prism instance or its worlds.

After validation, the stopped `LODGen-Testing` instance received the matching production jar and `globalExecutorParallelism = 32`. Both previous files were backed up under the instance's `lodgen-backups/` directory, outside `mods/`. Its worlds, other mods, LODgen settings and JVM options were retained. `instance-update.json` records the backup location and before/after hashes. LODgen itself does not automatically rewrite C2ME configuration.

## Build and runtime compatibility

| Minecraft | Loader | Build / unit tests | Packaged client | Packaged server |
| --- | --- | --- | --- | --- |
| 1.21.1 | Fabric | PASS / 48 | PASS | PASS |
| 1.21.1 | NeoForge | PASS / 48 | PASS | PASS |
| 26.1.2 | Fabric | PASS / 48 | PASS | PASS |
| 26.1.2 | NeoForge | PASS / 48 | PASS | PASS |
| 26.2 | Fabric | PASS / 48 | PASS | PASS |
| 26.2 | NeoForge | PASS / 48 | PASS | PASS |
| 26.3 | Fabric | PASS / 48 | PASS | PASS |
| 26.3 | NeoForge | PASS / 48 | PASS | PASS |

The eight builds execute **384 passing unit tests**, with no failures, errors or skipped cases. The new regression checks compact distant dispatch and the first center tile; existing tests cover complete unique traversal, odd/negative centers, shrink/expansion, world edges, spatial selection, task persistence and saved-radius rounding.

There are **16 DH/C2ME client/server startup checks**, with no worlds opened. Each packaged client renders the single scrollable nine-setting config page; all four Fabric clients exercise the Mod Menu config factory. Dedicated servers load applicable generation mixins. Java 21 runs 1.21.1; Java 25 runs 26.x. Every installable production class matches its startup fixture byte-for-byte, and test probes are excluded from installable jars. Artifact hashes, class counts and runtime reports are collected with the package.

Three additional **Voxy-only** checks omit DH: 1.21.1 NeoForge with Roxy/Voxy 0.2.16-beta, 26.1.2 Fabric with Voxy 0.2.18, and 26.2 Fabric with Voxy 0.2.19. They also force the integrated-server class through Mixin transformation, checking the early-shutdown hook. Only 26.1.2 opens a small world for this update; older-version world checks are not repeated.

## Small 26.1.2 correctness checks

The Voxy world/restart check uses **at most 88 targets**. It verifies real voxel storage, block/biome/light snapshot and mip parity with Voxy's original converter, all center modes, four inner native saves, and zero outer chunk/POI/entity saves. Far Overworld/Nether command tasks run without visiting the Nether. Start/status/pause/continue/stop, native action-bar ownership and hiding at zero are exercised. Completed tile coverage and stored voxel data survive reopening.

The DH command regression uses a **5c radius / 100 targets**, a **1c saved radius / four native saves**, and four additional automatic fixed-center targets. It checks command controls and arguments, pauses with work incomplete, closes a running task, and resumes its persisted frontier after restart. Region-header audits require exactly four inner native chunks, with no outer target/supporting chunk, POI or entity saves. This also exercises the idempotent task shutdown hook with DH.

```sh
python3 scripts/build-all.py
xvfb-run -a python3 scripts/startup-test.py --modmenu
xvfb-run -a python3 scripts/voxy-test.py --mc 1.21.1
xvfb-run -a python3 scripts/voxy-test.py --mc 26.1.2 --world --reload
xvfb-run -a python3 scripts/voxy-test.py --mc 26.2
python3 scripts/integration-test.py --mc 26.1.2 --loader fabric \
  --java /path/to/java25 --run-name regression-sustained-tasks \
  --task-check --quick --heap 32G
```

Build variants sequentially with JDK 25 and a Java 21 toolchain because Unimined shares remapping data. The tests use disposable worlds. Earlier DH native/Chunky and distance checks, CPU-level details, and short 26.2 benchmarks are retained in [docs/VALIDATION-utilization-0.0.0.md](docs/VALIDATION-utilization-0.0.0.md). Those reports describe the prior development build.
