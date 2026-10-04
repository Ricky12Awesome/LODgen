# LODgen development validation — 0.0.0

The utilization update replaces active/waiting batch limits and request grouping with five Voxy CPU-load levels. DH installations follow DH's live thread count and runtime ratio. Native work uses a larger heap-bounded window, compact spatial batches, coalesced ticket updates and immediate refill after conversion. Voxy avoids redundant uniform-air conversion, and DH amortizes repeated waiting-queue scans.

Performance tests use **26.2 Fabric**, vanilla worldgen, **32 GB maximum heap**, Java 25, ZGC, compact object headers, C2ME/OpenCL and the requested optimization stack. The best measured Voxy run averaged **1,873 completed target chunks/s**, comparable to its native-only Chunky baseline. **2,500/s was not reproduced**, including by native-only Chunky.

All eight targets build and pass packaged client/server startup. The version remains **0.0.0**, and the GitHub workflow is unchanged. World checks are limited to 26.2 for this update.

## Performance measurements

Hardware: Ryzen 9 9950X (32 logical CPUs), RTX 5070 Ti (16 GB), 62 GiB system RAM, NVIDIA driver 615.71.09. OpenCL initialized on the physical NVIDIA device. Client tests use Xvfb/Mesa software rendering at 30 FPS, vanilla view distance 2, and disabled Voxy LOD rendering. Actual voxel conversion, updates and storage remain enabled. Other desktop applications were not stopped.

Each warmed comparison generates 16,384 warmup targets and 16,384 measured targets, equivalent to a square 64c radius. Chunky's iterator includes boundary targets and reports 16,641 actual completions. All generation stages and vanilla structures are enabled, with seed `123456789`. Supporting terrain is excluded from the rates. LOD-only benchmark areas are audited after shutdown for native chunk, POI and entity region files.

| Integration | C2ME workers | Measured targets | Time | Completed targets/s | Chunky targets/s |
| --- | --- | --- | --- | --- | --- |
| Voxy, full power | 32 | 16,384 | 9.093 s | 1,801.900 | 1,836.289 |
| Voxy, full power | 24 | 16,384 | 8.746 s | 1,873.329 | 1,827.316 |
| DH actual queue and database updates | 32 | 16,384 | 10.280 s | 1,593.767 | 1,617.860 |

Voxy's ordinary ingestion is disabled **only during the native Chunky baseline** to measure chunk pregen without extra renderer work. LODgen's measured phase retains real voxel conversion and storage. DH's measurement uses its actual queue, executor and asynchronous database updates. Background load and terrain differ between phases; these are not isolated microbenchmarks or long-duration throughput guarantees.

During the profiled 32-worker Voxy measurement, JVM CPU utilization averaged approximately **82% of the whole machine**, and overall system utilization averaged **95%**. Native Chunky's phase averaged about 86% JVM / 95% system. These are one-second JFR samples overlapping the measured phases. The JVM reported roughly 13 GiB used heap during the Voxy phase; the 32 GiB allocation is a maximum.

Pinned mods are in `test-versions.json`: C2ME OpenCL, ScalableLux, Lithium, FerriteCore, Structure Layout Optimizer and its Resourceful Config dependency, zFastNoise, and Chunky. zFastNoise **1.0.40** includes the surface optimization mixins omitted by 1.1.1. No custom worldgen mods are installed.

Evidence is copied to `dist/validation-lodgen-0.0.0/performance/`. Original recordings remain in `build/26.2/fabric/voxy-performance/client/benchmark.jfr` and `build/26.2/fabric/selftest/performance-final/benchmark.jfr`. Benchmark fixtures preceded final error-path and stale-index safeguards; final small world checks and unit tests cover those safeguards.

```sh
xvfb-run -a python3 scripts/voxy-test.py --mc 26.2 --benchmark 64 \
  --cpu-load 5 --native-workers 24 --heap 32G --jfr --chunky-native-only

python3 scripts/integration-test.py --mc 26.2 --loader fabric \
  --java /usr/lib/jvm/java-25-openjdk/bin/java --run-name performance-check \
  --opencl --chunky --optimized --heap 32G --benchmark 128 --warmup-axis 128 \
  --workers 256 --dh-threads 32 --native-workers 32 --chunky-working-count 768 \
  --dh-queue --dh-executor --store-lods --jfr
```

## CPU load and scheduling

Voxy levels use DH's processor fractions: 10%, 25%, 50%, 75% and 100%, rounded upward. Level 1 also uses a 50% conversion duty ratio; level 3 is the default. On this 32-thread / 32 GB setup, worker counts are **4 / 8 / 16 / 24 / 32**, and admission windows are **4 / 16 / 32 / 96 / 256** native batches. Each full batch has 16 targets. A 128 MiB-per-batch heap-sizing heuristic accounts for targets and dependencies. Smaller heaps reduce admission; existing active work drains when settings decrease.

DH's advanced thread/runtime settings are queried live. Its original small in-progress cap is bypassed only for the builtin chunk-enabled FEATURES generator. Voxy and command tasks resize conversion pools instead of retaining a fixed eight-worker maximum. C2ME's global worker configuration is not rewritten. These presets are not strict CPU caps for the entire Minecraft process.

Commands traverse compact 32×32 patches with exact square edges and at most 16 targets per batch. New task checkpoints require `layoutVersion = 1`; unversioned development checkpoints are not imported. Radius rounding, clipped borders, completion prefixes and restart progress have unit coverage across uneven patch sizes.

The backend claims shared dependency rectangles and processes admissions/releases together. Immediate completion-driven refill keeps native work available. DH's cached selection heap validates task identity and current bounds on every selection and rebuilds on movement, growth, exhaustion or 64 selections. Tests cover replaced tasks, stale final candidates and new work.

Voxy snapshots preserve block, biome and lighting data. Missing sky layers above the highest occupied section share a full-sky array. Uniform air uses the same air/light mapping as Voxy's converter, followed by normal mip generation and storage updates. The real-world check compares all base voxels and generated mips against Voxy's original converter and samples native light values across every section, including **244 optimized air sections**.

## Build and startup matrix

| Minecraft | Loader | Build / unit tests | Packaged client | Packaged server | Cached startup check |
| --- | --- | --- | --- | --- | --- |
| 1.21.1 | Fabric | PASS / 47 | PASS | PASS | 19.45 s |
| 1.21.1 | NeoForge | PASS / 47 | PASS | PASS | 19.16 s |
| 26.1.2 | Fabric | PASS / 47 | PASS | PASS | 16.97 s |
| 26.1.2 | NeoForge | PASS / 47 | PASS | PASS | 17.25 s |
| 26.2 | Fabric | PASS / 47 | PASS | PASS | 16.78 s |
| 26.2 | NeoForge | PASS / 47 | PASS | PASS | 17.17 s |
| 26.3 | Fabric | PASS / 47 | PASS | PASS | 22.77 s |
| 26.3 | NeoForge | PASS / 47 | PASS | PASS | 18.39 s |

There are **376 passing unit-test executions**, with zero failures, errors or skipped tests, plus **16 successful DH/C2ME runtime checks**. Java 21 is used for 1.21.1; Java 25 for 26.x. Startup times include cached fixture builds and setup. Exact loader/DH/C2ME/Mod Menu versions are in `versions.json`.

Each packaged client renders LODgen's single scrollable **nine-setting** page, exercising wheel scrolling, focus, signed coordinates, draft retention across resizing, center selection and CPU-load controls. With DH installed, the CPU control is disabled and points to DH; Voxy-only clients cycle all five levels. Native action-bar priority and zero-rate checks remain. All four Fabric clients also exercise Mod Menu's config entrypoint.

Dedicated-server probes load every applicable generation mixin before world initialization. Client probes open no worlds. No chunk/POI/entity region files may be created. These are basic runtime compatibility checks, not terrain or visual LOD-rendering tests.

Every installable production class matches its packaged startup fixture byte-for-byte; test probes are excluded from installable jars. Results, class counts and SHA-256 values are in `dist/validation-lodgen-0.0.0/results.json` and `artifact-audit.json`. Original logs remain in `build/<minecraft>/<loader>/startup/`.

## Small 26.2 regression checks

The DH native regression runs its actual queue and database updates alongside a small real Chunky pregen, using C2ME/OpenCL and the requested optimization stack. It verifies nonempty lit LODs, shared requests and live thread-limit changes. The LOD-only area has no native chunk, POI or entity files. Normal/adopted gold and diamond edits survive restart; cold FEATURES reads leave the existing native region byte-for-byte unchanged. Its 8×8 benchmark area is a correctness check.

The command check uses a **5c radius / 100 targets**, a **1c saved radius / four native saves**, and four additional fixed-center automatic DH targets. It executes start/status/pause/continue/stop, checks origin, optional saved radius and block rounding, closes with incomplete running work, and resumes automatically after restart. The fixture explicitly gives DH one worker so this tiny task is not entirely admitted before pausing. Active batches drain; new dispatch stops. The region-header audit finds only the four inner native saves and no outer target/supporting chunk, POI or entity writes. The audit accounts for 26.x's dimension-specific Overworld folder.

The distance check sets custom distance **64** and DH distance **128**, requesting only two 4×4 sections (32 targets). The section at 60 chunks generates; the section at 96 is cancelled before native generation. Resetting to 0 live allows the second section without changing DH's 128 distance. Shutdown audits cover both regions. Boundary and 512-to-128 behavior also have pure unit coverage.

The Voxy-only 26.2 world/restart checks cover at most **88 targets**, real voxel storage, four inner native saves, far Overworld/Nether commands without visiting the Nether, all center modes, pause/resume/stop, action-bar throughput/ownership, snapshot/light/mip parity, and persisted coverage after reopening. DH is absent. Additional Voxy-only **startup** checks cover 1.21.1 NeoForge (Roxy + Voxy 0.2.16-beta) and 26.1.2 Fabric; their terrain tests are not repeated.

```sh
python3 scripts/integration-test.py --mc 26.2 --loader fabric --java /path/to/java25 \
  --run-name regression-tasks --task-check --quick --opencl --optimized --heap 32G
python3 scripts/integration-test.py --mc 26.2 --loader fabric --java /path/to/java25 \
  --run-name regression-distance --distance-check --quick --opencl --optimized --heap 32G
xvfb-run -a python3 scripts/voxy-test.py --mc 26.2 --world --reload
```

## Reproduction and scope

```sh
python3 scripts/build-all.py
xvfb-run -a python3 scripts/startup-test.py --modmenu
xvfb-run -a python3 scripts/voxy-test.py --mc 1.21.1
xvfb-run -a python3 scripts/voxy-test.py --mc 26.1.2
```

Use JDK 25 for Gradle with the Java 21 toolchain installed. Build targets sequentially because Unimined shares remapping data. Tests use disposable directories; original Prism instances are untouched. The workflow retains its existing steps.

Visual Voxy rendering, remote multiplayer generation, custom worldgen throughput and long-duration full-distance rates are outside this pass. Earlier checks are retained in [docs/VALIDATION-config-page-0.0.0.md](docs/VALIDATION-config-page-0.0.0.md), [docs/VALIDATION-0.0.0-initial.md](docs/VALIDATION-0.0.0-initial.md) and [docs/VALIDATION-0.2.1.md](docs/VALIDATION-0.2.1.md). Their unchanged-generation claims and predecessor rates describe those historical builds, not this update.
