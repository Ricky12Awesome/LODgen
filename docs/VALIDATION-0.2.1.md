# Validation — 0.2.1, 2026-09-30

This update is built and tested **only for Minecraft 1.21.1 NeoForge**, as requested. Both release and self-test builds pass 15 unit tests. The release jar's 30 class files match the packaged runtime test jar byte-for-byte; integration entrypoints and dependencies are excluded.

## Pack setup

Disposable dedicated servers use selected worldgen jars and settings copied from the Create Aeronautics Modpack instance:

- NeoForge **21.1.252**, matching the instance; DH **3.3.3**.
- C2ME and its optional OpenCL addon **0.4.0-alpha.0.122**, ScalableLux **0.3.0-alpha.0.8**, Chunky **1.4.23**.
- William Wythers' Overhauled Overworld **2.6.7**, Continents **1.1.14**, Cristel Lib **3.1.7**, Lithostitched **1.8.0** with its embedded Apollib.
- Copied DH settings: horizontal and vertical quality **MEDIUM**, LOD radius **512**, **32** DH threads, runtime ratio **1.0**, `CHUNKS_ONLY` plan with `FEATURES` mode.
- **15 actual C2ME workers**, asserted at runtime, and `-Dchunky.maxWorkingCount=768`, matching the supplied Spark profile. These overrides reproduce the client values on a dedicated server.

Hardware: Ryzen 9 9950X / RTX 5070 Ti, NVIDIA driver 615.71.09. Runtime: Java 25.0.4.1, `-Xmx8G`, default G1 collector. Seed: `123456789`. The original instance's world, mods and settings are not modified. No OpenCL, Chunky, or worldgen dependency is bundled with the addon.

## Measurements

| Test | DH chunks | DH elapsed | DH chunks/s | Chunky chunks | Chunky elapsed | Chunky chunks/s |
| --- | --- | --- | --- | --- | --- | --- |
| Eight-batch, distance-only control; frontier radius 256 | 16,384 | 64.745 s | **253.1** | 16,641 | 33.928 s | 490.5 |
| New defaults; frontier radius 256 | 16,384 | 41.481 s | **395.0** | 16,641 | 34.238 s | 486.0 |
| New defaults; sustained frontier radius 512 | 65,536 | 116.273 s | **563.6** | 66,049 | 116.203 s | **568.4** |

The controlled scheduling comparison improves DH throughput by **56%**. Both cases use the same new save fix and duplicate-update guard; only the batch window and spatial ordering differ. Preliminary runs on NeoForge 21.1.228 were used for diagnosis and are excluded from this table.

Each process first generates a separate 256-chunk warmup area. The measured workload uses DH's **actual WorldGenerationQueue** and its actual executor, including request selection, its 33-request admission limit, normal native FULL generation, lighting snapshots, LOD conversion, asynchronous LOD database updates, and ticket cleanup. Queue requests start around a distant frontier to reproduce later-session locality without pregenerating its interior. The benchmark size determines target count; a frontier workload is a thin square ring, not a filled square.

Chunky runs afterward in a separate fresh area of the same seeded world. Its square selection includes an extra boundary row/column. Timing waits for its final asynchronous requests to drain, beyond its initial completion event. Terrain complexity and the extra DH conversion/database work differ; equal rates in the sustained fixture do not guarantee equal rates in every modpack.

The sustained DH run lasts nearly two minutes and samples heap use at **1,684–4,023 MiB** every five seconds. Intervals vary with terrain complexity. [Machine-readable results and samples](../dist/validation-0.2.1/results.json) retain the counts and measurements.

## Correctness

All three measured runs pass the chunk/POI/entity file audit **after forced saves, shutdown and restart**. Actual Chunky runs concurrently with overlapping DH requests. Normal and cached-adopted chunks preserve gold/diamond block edits through restart. A subsequent cold FEATURES read leaves an existing region file byte-for-byte unchanged.

The smoke checks also verify shared ticket references, one callback per pooled DH source, 12,288 populated LOD columns, and native block/sky light snapshots. A separate 1.21.1 NeoForge check exercises the addon without C2ME/OpenCL or Chunky. Unit tests cover ownership, admission/shutdown/cancellation, spatial priority at negative/world-border coordinates, and scope restoration after errors and across concurrent normal generation.

The larger fixture exposed a save leak that small vanilla tests missed: village cats perform server structure lookups while a feature runs. Those lookups were incorrectly adopting transient terrain. A `try`/`finally` ownership scope now keeps them transient; explicit forced/player/portal tickets retain normal save ownership. The corrected frontier checks exercise the affected seeded terrain and pass.

In the C2ME/OpenCL runs, optional-client-class and early Chunky lifecycle messages occur during dedicated-server startup; the fixture still completes and no generation future, lighting assertion, or final storage audit fails. A separate attempt with DH + Chunky and no C2ME aborts during initial spawn setup in DH's Chunky accessor (`Chunky is not loaded`), before the integration checks. That combination is not validated; concurrent Chunky generation is validated with C2ME/OpenCL.

## Profile findings and limits

The [supplied Spark profile](https://spark.lucko.me/yDK9HTp2Ku) contains wall-time samples with DH's generation pool about 98.7% parked and C2ME workers about 49.6% parked. It also shows ordinary DH chunk-update conversion/lighting alongside explicit FEATURES conversion. The small 128-target window and scattered frontier selection leave native workers waiting; grouping nearby requests and widening the window reduce that problem.

The profile records about **24.5 GiB of swap in use**, a 40 GB JVM maximum and a 33.6 GB used heap. Memory pressure may contribute to the full client's longer-session slowdown. This fixture uses an 8 GiB dedicated server and does not reproduce the entire client's memory workload.

Medium settings are copied, but client rendering and the remaining pack mods are outside this fixture. Nether/End generation and upgraded-world blending remain untested. The older eight-target vanilla checks are historical evidence for 0.2.0 in [docs/VALIDATION-0.2.0.md](VALIDATION-0.2.0.md); they do not validate this patch on other targets.

## Reproduce

```sh
python3 scripts/integration-test.py --mc 1.21.1 --loader neoforge --opencl --chunky \
  --benchmark 256 --dh-executor --dh-queue --store-lods --frontier-radius 512 \
  --native-workers 15 --chunky-working-count 768 \
  --worldgen-instance '/path/to/Prism/instance/minecraft' --java /path/to/jdk25/bin/java
```

Use `--benchmark 128 --frontier-radius 256` for the shorter comparison. Add `--pipeline-batches 8 --no-spatial-batching` for the control. Add `--jfr` for Java Flight Recorder evidence. The script accepts the EULA only for its disposable server and recreates only its own test world. It verifies saved files again after shutdown and performs a separate restart check.

Logs and the sustained JFR recording remain in `build/1.21.1/neoforge/selftest/packaged-server/`. Without-C2ME checks use `packaged-server-vanilla/`. The release bundle includes configuration defaults, results, five-second samples, checks, and the SHA-256 manifest.
