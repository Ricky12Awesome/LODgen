# Validation — 0.2.0, 2026-09-30

All eight release targets build and pass nine shared unit tests. Packaged-server tests use actual DH, C2ME, and Chunky releases, with opt-in integration entrypoints excluded from the release jars. The linked C2ME OpenCL addon and ScalableLux are installed for every target that has a published OpenCL release.

| Minecraft | Loader | Unit tests | Generation, lighting, concurrent Chunky | Shutdown and restart audit | OpenCL installed |
| --- | --- | --- | --- | --- | --- |
| 1.21.1 | Fabric | 9 passed | Passed | Passed | Yes |
| 1.21.1 | NeoForge | 9 passed | Passed | Passed | Yes |
| 26.1.2 | Fabric | 9 passed | Passed | Passed | Yes |
| 26.1.2 | NeoForge | 9 passed | Passed | Passed | Yes |
| 26.2 | Fabric | 9 passed | Passed | Passed | Yes |
| 26.2 | NeoForge | 9 passed | Passed | Passed | Yes |
| 26.3 | Fabric | 9 passed | Passed | Passed | No release available |
| 26.3 | NeoForge | 9 passed | Passed | Passed | No release available |

Additional tests pass with C2ME absent on 1.21.1 Fabric and 26.3 NeoForge. This verifies that OpenCL and C2ME are optional.

## Runtime checks

Each world uses vanilla overworld terrain and seed `123456789`.

Three DH requests produce 12,288 populated LOD columns and exactly one callback per original pooled source. Two requests share the same 4×4 native chunk area, exercising reference-counted ticket ownership and independent conversion. The isolated area around `(4096, -4096)`, including generation dependencies and a lighting test area, creates no chunk, POI, or entity region files. The audit runs after forced saves, shutdown, and restart.

Actual Chunky tasks run concurrently with DH at `(8192, -8192)`. Normal FULL generation retains a gold-block edit. A completed transient chunk is subsequently adopted through a normal cached request and receives a diamond-block edit. Both edits survive server restart.

On restart, a cold FEATURES request reads those existing chunks before any normal request. A forced save leaves their chunk region file byte-for-byte unchanged. Subsequent normal reads find both edits. This tests loading saved terrain for LODs without changing its persistence ownership.

A separate held-ticket batch compares block and sky lighting snapshots against the installed native lighting engine across sample columns and heights, including absent sky-light arrays. Tests exercise both ScalableLux and vanilla lighting.

Unit tests cover shared admission limits, queued/active shutdown, executor rejection, protection against premature pooled-data cancellation, negative-coordinate ownership masks, normal adoption of dependency areas, requests preceding holder creation, loaded chunks, and irreversible promotion across repeated requests.

## Throughput measurements

Hardware: AMD Ryzen 9 9950X, NVIDIA GeForce RTX 5070 Ti, NVIDIA driver 615.71.09. Runtime: OpenJDK 25.0.4.1, `-Xmx8G`, default C2ME settings, eight DH conversion workers. The OpenCL driver enumerates the GPU and compiles the world generation programs successfully.

| Target | DH target chunks | DH elapsed | DH chunks/s, including conversion | Chunky chunks | Chunky elapsed | Chunky chunks/s |
| --- | --- | --- | --- | --- | --- | --- |
| 1.21.1 Fabric | 4,096 | 8.447 s | 484.9 | 4,225 | 5.060 s | 835.0 |
| 26.2 Fabric | 4,096 | 7.382 s | 554.8 | 4,225 | 5.295 s | 797.9 |

A separate 256-chunk DH area warms up CPU/GPU work before each measured run. Eight asynchronous feeders keep contiguous 4×4 DH tiles in flight; their futures include chunk generation, lighting snapshots, LOD conversion, and ticket release. Chunky's native square selection contains 4,225 chunks. Its benchmark waits for every asynchronous chunk request to drain, because its completion event can precede the final requests finishing.

DH and Chunky use separate fresh areas of the same world, so terrain complexity can differ. DH also performs LOD conversion. These measurements demonstrate the new path's throughput on this fixture; they do not establish a universal speedup, equal throughput to Chunky, or performance in the user's full modpack. C2ME logs its nonfatal sculk-feature terrain-read diagnostics during the DH benchmark; no generation future or storage audit fails. Some upstream NeoForge startup logging/lifecycle warnings are also present before the test starts.

The 0.1.0 private graph is removed. These results apply to the new normal-pipeline implementation and are not a controlled comparison with 0.1.0 on the same world.

## Reproduce

```sh
python3 scripts/build-all.py
python3 scripts/integration-test.py --mc 1.21.1 --loader fabric --opencl --chunky --benchmark 64 --java /path/to/jdk25/bin/java
python3 scripts/integration-test.py --mc 26.2 --loader fabric --opencl --chunky --benchmark 64 --java /path/to/jdk25/bin/java
python3 scripts/integration-test.py --mc 26.3 --loader neoforge --chunky --java /path/to/jdk25/bin/java
```

Repeat the integration command for each target, omitting `--opencl` for 26.3. Use `--vanilla` without `--opencl` to test without C2ME. Exact mod release IDs are in `versions.json` and `test-versions.json`.

Logs and reports are under `build/<minecraft>/<loader>/selftest/packaged-server/`; vanilla checks use `packaged-server-vanilla/`. A benchmark produces `benchmark-result.json`. The release bundle contains the eight installable jars, their SHA-256 manifest, and the validation record.

Checks use dedicated servers. The full modpack, client rendering, Nether/End generation, upgraded-world blending, arbitrary normal-generation replacements, and mod-specific persistence beyond region/POI/entity storage remain untested.
