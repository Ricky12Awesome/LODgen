# LODgen development validation — 0.0.0

This update fixes integration and direct benchmark callers that submitted more FEATURES requests than the live native batch limit permits. Runtime code and production jars are unchanged. The version remains **0.0.0**, and the GitHub workflow is unchanged.

## CI admission regression

A fresh 26.1.2 Fabric server with two CPUs exposed reproduced the reported failure: three direct test requests hit a two-batch gate, causing `RejectedExecutionException: LOD generation queue full`. Cached DH thread settings on a larger machine masked the failure. The test caller now waits for capacity using the same live admission check as DH's normal queue. It preserves concurrent requests when capacity permits and propagates native failures.

The existing workflow build and integration commands passed locally for all eight targets with fresh DH configuration and `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2`. JDK 21 runs 1.21.1 servers; JDK 25 runs 26.x servers. No integration options, workload checks or production limits were disabled or raised for this matrix.

| Minecraft | Loader | Production build / tests | Integration build / tests | World check | Reopen check |
| --- | --- | --- | --- | --- | --- |
| 1.21.1 | Fabric | PASS / 59 | PASS / 62 | PASS | PASS |
| 1.21.1 | NeoForge | PASS / 59 | PASS / 62 | PASS | PASS |
| 26.1.2 | Fabric | PASS / 59 | PASS / 62 | PASS | PASS |
| 26.1.2 | NeoForge | PASS / 59 | PASS / 62 | PASS | PASS |
| 26.2 | Fabric | PASS / 59 | PASS / 62 | PASS | PASS |
| 26.2 | NeoForge | PASS / 59 | PASS / 62 | PASS | PASS |
| 26.3 | Fabric | PASS / 59 | PASS / 62 | PASS | PASS |
| 26.3 | NeoForge | PASS / 59 | PASS / 62 | PASS | PASS |

Each world check still produces 12,288 LOD columns, checks lighting and shared output ownership, verifies distant LOD-only chunks create no native chunk/POI/entity files, and confirms overlapping normal FULL generation preserves a player edit and saves normally. Reopening verifies persisted edits and byte-for-byte preservation of an existing region loaded through FEATURES.

The integration builds execute **496 unit tests**, including three new regressions per target for one-slot/two-slot admission, a lowered live limit, failure propagation and close/cancellation ownership. The production builds execute the existing **472 tests**. All have zero failures, errors or skips. Production class bytes match the integration fixtures, and production jar hashes match the previous runtime validation.

A separate 26.1.2 Fabric direct benchmark smoke check passed with eight requested workers, a two-batch limit and 64 target chunks, including real DH storage updates and reopening. This tiny check makes no performance claim. Evidence is in `dist/validation-lodgen-0.0.0/ci-admission/`.

The runtime behavior and previously completed startup, DH plan and Voxy checks below remain applicable to the unchanged production jars.

## Generation behavior

| DH plan | Behavior |
| --- | --- |
| Surface Then Chunks | DH’s rough surface path remains active. Its FEATURES chunk phase uses LODgen even with the addon automatic toggle off. Fixed-area jobs complete a normal DH rough pass before native chunk generation. |
| Surface Only | DH uses its rough generator. LODgen adds FEATURES chunks only with its automatic toggle on, for current or fixed centers. |
| Chunks Only | DH FEATURES requests use LODgen, including when the addon automatic toggle is off. |
| Disabled | All automatic LODgen generation stops, including saved-radius pregen and Voxy when DH is present. Commands, including resumed command tasks, remain available. |

DH’s other chunk generator modes and API generator overrides retain their normal behavior. Disabling or changing automatic work allows already-admitted native batches to finish conversion and release tickets. Commands are exempt from the automatic policy.

The native queue ranks patches relative to the selected center, rather than absolute world coordinates. Rough tasks keep DH’s distance/detail ordering and thread-count admission limit, ahead of chunk tasks. Rough surfaces can extend to DH’s render distance beyond a custom native generation radius. Fixed-area jobs suppress duplicate native viewport work without blocking DH’s rough requests; constant-time pending/active counters avoid repeated map scans when only those suppressed requests remain.

Square jobs use center-out patch rings and center-out batches within each patch. Exact clipped edges, partial batches, prefix/out-of-order progress and saved subsets are preserved. Grid ordinals and chunk-prefix counts are computed without enumerating or allocating the entire area, including at the maximum world-sized radius. The changed traversal uses checkpoint layout **2**; older development checkpoints are not imported.

## Verification

| Minecraft | Loader | Build / unit tests | Packaged client | Packaged server |
| --- | --- | --- | --- | --- |
| 1.21.1 | Fabric | PASS / 59 | PASS | PASS |
| 1.21.1 | NeoForge | PASS / 59 | PASS | PASS |
| 26.1.2 | Fabric | PASS / 59 | PASS | PASS |
| 26.1.2 | NeoForge | PASS / 59 | PASS | PASS |
| 26.2 | Fabric | PASS / 59 | PASS | PASS |
| 26.2 | NeoForge | PASS / 59 | PASS | PASS |
| 26.3 | Fabric | PASS / 59 | PASS | PASS |
| 26.3 | NeoForge | PASS / 59 | PASS | PASS |

The eight targets execute **472 unit tests**, with zero failures, errors or skipped cases. New coverage checks the plan/toggle truth table, non-FEATURES eligibility, disabled Voxy policy, first-batch center coverage, outward patch rings, exact weighted progress across clipped/partial edges and random access at the maximum radius. Queue-priority tests cover negative coordinates, center-relative patches and opposite world borders.

All **16 packaged client/server startup checks** load DH and C2ME without opening a world. Clients check the config screen, vanilla action bar and Fabric Mod Menu factory. Production classes match packaged fixture classes byte-for-byte; installable jars exclude test probes. Voxy-only client startup covers all three supported targets; only 26.1.2 Fabric opens a small world and reopens it.

The **26.1.2 Fabric DH plan check** generates **156 native LOD targets** with at most a **5c** automatic radius. It exercises the actual DH rough generator and native dispatch queue, both chunk-enabled plans with the addon automatic toggle off, center-before-edge selection, Surface Only opt-in chunks, mid-task disable/drain/re-enable, disabled saved-radius generation, commands with DH disabled and fixed-area surface-before-chunk sequencing. No native, POI or entity region files appear in its distant test areas after shutdown. Its worker limit is explicitly pinned through DH’s config API so the live-disable check observes one batch at a time.

The existing small command/restart regression uses **5c / 100 targets**, a **1c saved radius / four native saves**, plus four automatic fixed-center targets. It checks command controls, unfinished-task resume with the new traversal, status/ETA, rough-first fixed generation and exact region-header persistence boundaries. The Voxy world/reload check uses at most **88 targets** and retains snapshot/light/mip parity, saved-radius, action-bar and command-dimension checks. These earlier checks did not repeat large performance runs or other-version worlds; the CI admission matrix above now covers the default world fixture on all targets.

The baseline integration runner now disables DH hooks through a test-only mixin plugin. Turning off the production automatic toggle intentionally keeps DH chunk-phase interception active, so it no longer represents a builtin-DH benchmark.

```sh
python3 scripts/build-all.py
xvfb-run -a python3 scripts/startup-test.py --modmenu
python3 scripts/integration-test.py --mc 26.1.2 --loader fabric --quick --dh-plan-check
python3 scripts/integration-test.py --mc 26.1.2 --loader fabric --quick --task-check
xvfb-run -a python3 scripts/voxy-test.py --mc 1.21.1
xvfb-run -a python3 scripts/voxy-test.py --mc 26.1.2 --world --reload
xvfb-run -a python3 scripts/voxy-test.py --mc 26.2
```

Evidence, artifact hashes and byte audits are collected in `dist/validation-lodgen-0.0.0/`. All checks use disposable directories. The stopped testing instance received its matching 26.1.2 Fabric production jar during the previous runtime update, with a backup outside `mods/`, preserving its configuration.

Previous display validation is retained in [docs/VALIDATION-progress-0.0.0.md](docs/VALIDATION-progress-0.0.0.md); prior sustained-generation measurements remain in [docs/VALIDATION-sustained-0.0.0.md](docs/VALIDATION-sustained-0.0.0.md). No new performance claim is made for this update.
