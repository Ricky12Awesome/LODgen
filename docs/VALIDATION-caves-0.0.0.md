# LOD-only cave modes — 0.0.0

Recorded 2026-10-03. Version remains **0.0.0**. This follows the [WWOO variability investigation](VALIDATION-wwoo-jitter-0.0.0.md).

## Behavior

**Options → LODgen… → LOD caves** selects Generate, Fill or Empty. The TOML setting is `caveMode = "generate"`, `"fill"` or `"empty"`; Generate remains the application default.

- Generate retains normal terrain generation.
- Fill removes the cave selector and noodle minimum from the noise density graph before native generation. It disables aquifers and ore veins, bypasses carvers, rejects underground structure starts, and skips deep concrete feature placements. Surface rules, trees, water and surface structures remain enabled.
- Empty uses the same pruning and generates a hollow terrain shell directly through the density graph. Its cutoff remains inside native interpolation, retaining ocean floors and overhangs without sampling expensive terrain noise at every voxel. Deeper terrain starts as air. Shell thickness follows terrain density; it is not a fixed number of blocks.

There is no generated-cave removal or filling pass. Empty restores surface ocean water before surface rules; it does not restore underground water or lava. Its ocean height/column queries respect the caller's build range.

Fill/Empty use isolated native ServerLevels with separate holders, lighting and temporary storage. Their policies suppress every chunk/POI/entity write and cannot promote a chunk into ordinary ownership. They never enter the server's dimension list or DH's dimension list. Supporting terrain follows the same mode. Server shutdown closes and removes their temporary directories.

Saved-radius targets, player/Chunky requests and existing saved regions use the original generator. Mixed requests convert both native sources into the original renderer dimension, reading lighting from the appropriate source. An ordinary visit after LOD generation creates normal terrain with caves. Cached Generate LOD-only holders cannot prevent switching to Fill/Empty.

Automatic tasks restart their area when the mode changes. Explicit tasks pin their mode and preserve it in layout-3 checkpoints. Existing checkpoints without a mode default to Generate. Existing LODs outside newly requested areas are not cleared.

The transformation recognizes vanilla Overworld-style density formulas, including WWOO, large-biome and amplified variants. Other dimensions, generator types and unrecognized formulas retain Generate. The 26.2 cached terrain selector and 26.3 registered density holder, feature placement interface, material system and carver signature are handled separately.

## WWOO palette markers and surface checks

WWOO uses the bottom eight layers as palette metadata. Later placement modifiers read those markers before moving to the surface to place trees and terrain. Dropping every deep placement would also drop those surface features.

Fill preserves the basal palette operations. Empty directs their queries and writes into a separate metadata array seeded with bedrock/deepslate rules. Neither native terrain, lighting nor renderer output contains that array. Disk predicate reads honor it; native palette rejection cannot discard a virtual marker operation. Selectors run before their concrete child placements are filtered. Filtering uses the terrain floor captured before decoration, so tall trees cannot classify later surface features as underground.

The WWOO/OpenCL forest check at chunk coordinates **10240, -10240**, seed `123456789`, compares 64 targets per mode. All **75,389** checked surface blocks match between Fill and Empty before decoration, including the retained 16 layers below the floor. Generate contains **73,007** sampled deep cave-air blocks; Fill contains **zero** sampled deep air and Empty contains **zero** sampled deep solid blocks. Empty's entire basal metadata range is also asserted to contain physical air.

Near-surface log/leaf counts are **6,323 / 7,941 / 8,243** for Generate / Fill / Empty. Total log/leaf counts also include WWOO's basal markers, so total counts alone are not a tree comparison. Surface decoration is enabled and present; exact completed feature placement is not promised to be identical when surrounding terrain, random draws and concurrent neighboring writes change.

Earlier exploratory speed runs omitted some of those palette operations and therefore omitted surface vegetation. Their results are excluded from the final performance comparison below.

## Controlled performance comparison

Headless MC **26.1.2 Fabric**, with WWOO **2.7.0**, the testing instance's server-safe optimization mods/settings, DH/C2ME/OpenCL/ScalableLux, Ryzen 9 9950X, RTX 5070 Ti, Microsoft Java 25, 32 GiB heap, ZGC and compact object headers. Native and DH workers are both 32. Each run warms a separate **4,096-target** area, then measures **16,384 targets**: a **64c radius** / 128×128 chunk square. Admission permits 256 native 4×4 requests.

Measured work includes native FULL generation, lighting, actual DH conversion and asynchronous LOD database updates. Native saved chunks and supporting chunks are excluded from the CPS count. JFR, full terrain fingerprinting and client rendering are disabled. No competing builds or test servers run during measurement. Run order is Generate, Fill, Empty, Empty, Fill, Generate.

| Mode | First run CPS | Second run CPS | Mean CPS | Change |
| --- | ---: | ---: | ---: | ---: |
| Generate | 576.383 | 580.834 | **578.609** | Baseline |
| Fill | 678.409 | 657.533 | **667.971** | **+15.4%** |
| Empty | 735.752 | 721.258 | **728.505** | **+25.9%** |

Both modes make a measurable speed difference in this area. Empty averages **9.1%** faster than Fill. These are two repeats per mode, not a guarantee for every seed, biome, distance or rendered client workload. The final build also clamps ocean restoration to shorter structure-query build ranges; that bounds fix leaves the normal Overworld paths used by these measurements unchanged.

CPS remains variable. For example, Empty's first run records five-second completion rates of **505.6, 915.2, 624.0 and 764.8 CPS**. Different feature workloads and supporting terrain costs remain, and batches count as complete after conversion and ticket release. The HUD still uses its existing five-second rolling window. Skipping caves reduces work; it does not make batch completion uniform.

Reproduction:

```sh
./gradlew -PmcVersion=26.1.2 -Ploader=fabric -PselfTest=true build
python3 scripts/integration-test.py --mc 26.1.2 --loader fabric --skip-build \
  --run-name caves-empty --cave-mode empty \
  --worldgen-instance '/path/to/Prism/instance/minecraft' \
  --instance-optimizations --opencl --heap 32G --benchmark 128 --warmup-axis 64 \
  --workers 256 --native-workers 32 --dh-threads 32 --dh-executor --store-lods \
  --java '/path/to/java25'
```

Use `--cave-mode generate` or `fill` for the controls. The six retained runs are `cave-bench-{generate,fill,empty}-final{1,2}`.

## Correctness and compatibility

All eight production targets pass **57 unit tests each**, with no failures, errors or skips: Fabric and NeoForge for 1.21.1, 26.1.2, 26.2 and 26.3. Packaged native cave checks pass on all eight targets with two exposed CPUs and a 4 GiB heap. No check launches a window.

Native checks cover surface heights/materials, normal caves, solid Fill interior, hollow Empty interior, virtual markers remaining outside terrain, trees, underground structure filtering, a cached Generate-to-Fill change, clipped ocean queries, later player generation, and a mixed batch of four saved plus twelve LOD-only targets. Native/POI/entity region headers confirm that the twelve LOD-only targets do not save after shutdown. A separate 26.1.2 check passes without C2ME.

The Fill command/reopen check uses a 5c task and a 1c saved radius, resumes an unfinished task with the same mode, and saves exactly four targets. The Empty automatic/reopen check verifies autostart, pause across reopen, continue, shrinking a completed radius, cancellation and queue blocking. It also saves exactly four native targets. Both audit LOD-only dependencies for native/POI/entity writes.

All six WWOO performance runs additionally pass real DH output/lighting checks, overlap with normal FULL generation, saved player edits, and cold reopening of an existing region without changing its bytes.

Production LODgen class files match each target's final packaged fixture byte-for-byte. Integration/startup probes are absent from regular jars. Installable jars, hashes and retained evidence are collected in `dist/` and `dist/validation-caves-0.0.0/`. Disposable worlds remain under `build/`; testing does not edit the Prism instance's worlds.

## Installation

The matching 26.1.2 Fabric jar is installed in `LODGen-Testing/minecraft/mods/`, with **Empty** selected in `minecraft/config/lodgen.toml`. The previous jar and configuration are backed up outside the mods directory. `dist/validation-caves-0.0.0/installation.json` records the exact backup path and installed SHA-256. Other instance settings and worlds are unchanged.
