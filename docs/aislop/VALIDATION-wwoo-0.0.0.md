# WWOO performance validation — 0.0.0

Recorded 2026-10-02. Version remains **0.0.0**.

## Profile and change

The supplied [Spark profile](https://spark.lucko.me/7mAG5zF3Me) attributes about **58.7%** of the grouped `c2me-worker` sampled time to `DiskFeature.place`, with **46.5%** in `StateTestingPredicate.test` beneath it. Those percentages overlap. The instance also runs Distant Horizons SeedGen 0.8.0, whose feature evaluation appears separately on DH threads and contends on its painted-world access.

LODgen now compiles supported vanilla disk targets into a bounded short-circuit plan. Repeated offsets share one live state lookup within a test. Immutable explicit block lists avoid registry-holder membership checks. Tags retain their live evaluator. Native regions test simple center conditions before neighbor conditions; custom worlds keep the original order. A feature-scoped cache retains at most 16 native chunk references and releases them on exit, including nested calls. Block states are read live on subsequent tests.

A conservative native palette prefilter skips a whole disk when every relevant section rejects a necessary center-state condition. The original radius draw has already happened at the intercepted column iterator. Skipped placements cannot call a state provider or place a block. The proof handles negation, unknown neighbor conditions, empty sections normalized to AIR, and out-of-height VOID_AIR. It applies only to ordinary generation-owned `ProtoChunk` instances; other chunk implementations use normal evaluation. Unknown predicates and oversized trees keep the original target path.

DH task ownership now also applies to API generator overrides such as SeedGen. While a fixed-area task owns the chunk phase, the viewport queue waits instead of generating another chunk-feature workload. Surface requests remain available for surface plans. Turning automatic generation off releases the override queue; DH Disabled and paused/stopped ownership still block automatic work.

## Measured throughput

The headless benchmark copies the instance's WWOO 2.7.0/configuration and server-safe optimization stack. It uses Minecraft 26.1.2 Fabric, DH 3.3.3, C2ME/OpenCL `0.4.0-alpha.0.62+26.1.2`, ScalableLux, Lithium, FerriteCore, Structure Layout Optimizer and zfastnoise. WWOO's installed `removeOres: true` setting is included. Hardware is Ryzen 9 9950X / RTX 5070 Ti. Java 25 uses a 32 GiB heap, ZGC and compact object headers.

Each run warms a separate 64×64 area, then measures **16,384 chunks** in the same 128×128 area with seed `123456789`, 256 concurrent requests, 32 native workers and 32 DH workers. Measurement includes FEATURES generation, lighting and DH LOD conversion. JFR and terrain fingerprints are enabled in both variants. The control disables only the disk-feature optimization, retaining the same LODgen pipeline. SeedGen's client world generator and client rendering are outside this benchmark.

| Variant | Run directory | Chunks/s |
| --- | --- | ---: |
| Original disk evaluation | `wwoo-control-hash` | 455.316 |
| Original disk evaluation | `wwoo-control-hash-repeat` | 466.771 |
| Original disk evaluation | `wwoo-final-control` | 467.192 |
| Final disk optimization | `wwoo-palette-benchmark` | 566.899 |
| Final disk optimization | `wwoo-palette-repeat` | 499.022 |

The arithmetic means are **463.093 → 532.961 chunks/s**, about **15% higher**. The optimized repeats range from **499–567 chunks/s**, so the best result is not a guaranteed rate. These measurements do **not** establish parity with the reported Terralith/Tectonic 800–1,200 cps rate, or quantify the separate benefit of preventing competing SeedGen work in the client instance.

```sh
./gradlew -PmcVersion=26.1.2 -Ploader=fabric -PselfTest=true build
python3 scripts/integration-test.py --mc 26.1.2 --loader fabric --skip-build \
  --run-name wwoo-benchmark --worldgen-instance '/path/to/Prism/instance/minecraft' \
  --instance-optimizations --opencl --heap 32G \
  --benchmark 128 --warmup-axis 64 --workers 256 --native-workers 32 \
  --dh-threads 32 --dh-executor --jfr --verify-terrain --java '/path/to/java25'
```

Add `--original-predicates` for the control. Run variants separately, with no other generation or builds running during measurement.

## Correctness and compatibility

The final WWOO check compares **189,427,022** target evaluations against vanilla on the same live world, including every position in **75,234** skipped disk placements. All agree. Separate truth-table checks cover nested boolean targets, writes between tests, repeated offsets, short-circuit order in custom worlds, unknown-predicate fallback, necessary center conditions and concurrent evaluation.

```sh
python3 scripts/integration-test.py --mc 26.1.2 --loader fabric --skip-build \
  --run-name wwoo-verify --worldgen-instance '/path/to/Prism/instance/minecraft' \
  --instance-optimizations --opencl --quick --heap 4G \
  --benchmark 16 --workers 4 --native-workers 4 --dh-threads 4 \
  --dh-executor --verify-predicates --java '/path/to/java25'
```

The verifier is added to the packaged test fixture at runtime. Production hot paths contain no verification branch or duplicate vanilla evaluation. Whole-area fingerprints are recorded as diagnostics: even repeated original controls produce different hashes under parallel generation, so hash equality is not claimed as a proof of terrain equivalence.

All eight production targets build and pass **54 unit tests each**, with no failed, erroneous or skipped cases. All eight packaged runtime and reopen checks also pass. They use a small 8×8 benchmark and two exposed CPUs, checking real disk mixins/predicate comparisons, LOD output, lighting, no native/POI/entity files in LOD-only areas, overlapping normal FULL generation, player edits and reopen persistence. The 1.21.1 Fabric build uses explicit intermediary selectors; 26.3 handles disk configuration folded into the feature record.

The DH plan check passes both with and without the instance's **actual SeedGen 0.8.0 jar** installed in the disposable server. It exercises API overrides that do not implement LODgen's admission interface, duplicate chunk blocking, automatic-toggle release, Disabled, and retained rough requests. The largest automatic task is 5c; it generates 156 native LOD targets. Loading SeedGen also checks coexistence with its backlog mixin. No check launches a window or uses the instance's worlds.

```sh
python3 scripts/build-all.py
python3 scripts/integration-test.py --mc 26.1.2 --loader fabric --quick \
  --dh-plan-check --run-name seedgen-plans \
  --extra-mod '/path/to/Distant Horizons SeedGen 0.8.0 for 26.1.2 Fabric.jar' \
  --java '/path/to/java25'
```

Evidence, target summaries and installable artifact hashes are collected under `dist/validation-wwoo-0.0.0/`. All test worlds are disposable directories under `build/`.
