# WWOO throughput variability and allocation reduction — 0.0.0

Recorded 2026-10-02. Version remains **0.0.0**. This follows the [disk predicate optimization](VALIDATION-wwoo-0.0.0.md).

## Why the displayed rate varies

The supplied [123.5-second Spark profile](https://spark.lucko.me/docbDfnDmV) records 96.0% process CPU usage and 99.9% system CPU usage over its final minute. Server ticks remain at 20 TPS, with a 4.7 ms median. Of the grouped generation-worker samples, 83.2% are beneath biome decoration, 57.7% beneath `DiskFeature.place`, and 11.8% beneath `OreFeature.doPlace`. Disk rule-provider samples overlap the disk total; these are not independent percentages to add together.

This is a CPU-bound feature workload. Different terrain requires different numbers and types of placements, and a new generation frontier requires supporting neighbor chunks that are not counted as completed LOD targets. The sample does not include a synchronized CPS/biome trace, so it cannot assign a specific observed 200-CPS dip to a specific biome or collector cycle.

`ChunkThroughput` measures completed target chunks over a five-second rolling window. The native pipeline credits a batch only after its targets have generated, converted and released their tickets. Fixed tasks use batches of up to 16 targets. Slow batches and completion bursts therefore affect the display even while workers remain occupied. The averaging window and displayed rate have not been changed.

Spark reports a median allocation rate of **6.38 GB/s**, peaking at **7.93 GB/s**. ZGC performs concurrent collection frequently, consuming CPU and memory bandwidth alongside generation. Its reported average stop-the-world pauses are only 0.009 ms for minor pauses and 0.031 ms for major pauses. Long collector-cycle durations must not be interpreted as seconds-long game freezes.

## Change

Vanilla's ore loop creates a target-list iterator per candidate voxel and a bound block-reader function per target test. `OreFeatureMixin` now supplies a `BulkSectionAccess` subclass that is itself the reader and reuses one target cursor per placement. Discarding the unused vanilla reader reference lets the JIT eliminate its allocation.

The geometry loop, target order, random calls, live block reads, section locking, writes and section release remain vanilla's code. Reuse applies only to ordinary native `OreFeature` placements in `WorldGenRegion`, and only to the JDK's immutable random-access target lists. Mutable/custom lists, custom ore feature subclasses and other world implementations retain the original behavior. Each ore placement owns its cursor, so concurrent or nested placements do not share it.

DH readiness also handles an unloaded world and a disconnect between its readiness and wrapper queries. A normal renderer disconnect no longer records `No world loaded` as a task initialization failure.

## Measurements

The headless comparison uses the same WWOO/configuration and server-safe instance stack as the earlier validation: MC 26.1.2 Fabric, DH/C2ME/OpenCL/ScalableLux, Ryzen 9 9950X / RTX 5070 Ti, Java 25, 32 GiB heap, ZGC, compact headers, seed `123456789`, 32 native workers and 32 DH workers. It warms a separate 64×64 area. Both variants enable JFR and full terrain fingerprints and retain the disk optimization. The control disables only `OreFeatureMixin`. No competing tests or builds run during measurement. Client rendering and SeedGen's client generator are outside the comparison.

| 16,384 target chunks | Run | Chunks/s |
| --- | --- | ---: |
| Previous allocation behavior | `wwoo-jitter-control` | 535.010 |
| Previous allocation behavior | `wwoo-ore-control-repeat` | 625.723 |
| Reused ore readers/cursors | `wwoo-ore-benchmark` | 634.441 |
| Reused ore readers/cursors | `wwoo-ore-repeat` | 618.216 |

Arithmetic means are **580.367 → 626.329 CPS**, about **7.9% higher**. The ranges overlap substantially. Two repeats per variant are insufficient to claim a guaranteed speedup or elimination of jitter.

The larger **65,536-target** comparison measures **586.956 → 592.093 CPS**, only **0.9% higher**. This is effectively unchanged within the observed variability; a sustained CPS improvement is not established. Five-second completion rates continue to vary substantially and the LOD conversion queue stays empty. The optimized recording includes brief allocation-summary analysis on one CPU, so its result is also not a fully isolated measurement. These runs are `wwoo-jitter-large-control` and `wwoo-jitter-large-optimized`.

An additional direct disk section-read experiment passed live target checks but measured slower and was removed. The installed disk implementation remains the previously validated one.

JFR's weighted allocation samples for `wwoo-ore-control-repeat` and `wwoo-ore-benchmark` estimate **297.34 → 179.46 GiB** of allocations across their complete recordings, about **39.6% less**. The ore reader lambda and immutable list iterator cease to dominate the optimized recording. These are sampled allocation estimates, include startup/warmup/checks, and are not measurements of retained heap or stop-the-world pause reduction.

```sh
./gradlew -PmcVersion=26.1.2 -Ploader=fabric -PselfTest=true build
python3 scripts/integration-test.py --mc 26.1.2 --loader fabric --skip-build \
  --run-name wwoo-ore-benchmark --worldgen-instance '/path/to/instance/minecraft' \
  --instance-optimizations --opencl --heap 32G --benchmark 128 --warmup-axis 64 \
  --workers 256 --native-workers 32 --dh-threads 32 --dh-executor \
  --jfr --verify-terrain --java '/path/to/java25'
```

Add `--original-ore-allocations` for the control. JFR allocation inspection uses `jdk.ObjectAllocationSample` and `--stack-depth 128`.

## Correctness and compatibility

All eight production targets pass **54 unit tests each**, with zero failures, errors or skips. All eight packaged generation/reopen checks pass with live disk predicate verification. They use an 8×8 workload, two exposed CPUs and a 2 GiB heap. They verify native lighting and renderer output, no native/POI/entity files in LOD-only areas, overlapping normal FULL saves, retained player edits and reopen persistence.

Each target also verifies early-break/reset ore iteration, original ordering, exhaustion, mutable-list fail-fast fallback, native reader results and **768 rule/result/random-sequence comparisons**, including random-consuming rules and fractional air-exposure discard chances. The 1.21.1 Fabric mixin uses explicit intermediary selectors. The 26.3 mixin handles the folded ore feature/configuration and instance rule method.

The WWOO smoke check also passes **189,179,212** live disk target comparisons and verifies all positions in **75,104** skipped disks. No validation launches a window or changes the instance's worlds. Parallel terrain fingerprints remain diagnostic because even original control runs differ under parallel feature placement.

Evidence is collected under `dist/validation-wwoo-jitter-0.0.0/`; disposable worlds remain under `build/`.

The verified production 26.1.2 Fabric jar is installed in `LODGen-Testing/minecraft/mods/`. The previous jar is backed up under `LODGen-Testing/lodgen-backups/wwoo-jitter-20261002-230258/`. Installed SHA-256 is `95c9702f984cbe4b02ac00a6a970ff59f453d91b9c8525d1df37e06427e0a5c2`. Its production Minecraft classes match the final packaged test fixture byte-for-byte. Instance worlds and settings are unchanged.
