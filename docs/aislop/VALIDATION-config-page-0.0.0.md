# LODgen development validation — 0.0.0

All eight configured targets build and pass packaged **client and dedicated-server startup** with their pinned Distant Horizons and C2ME versions. This update replaces the config submenus with one scrollable list containing all eleven settings. The packaged clients exercise scrolling, focus on the last input, custom-coordinate controls and unsaved edits across resizing, then return to Options or Mod Menu. No worlds are opened for this screen change. The earlier small DH/Voxy command, saving, restart and Chunky checks are retained below; they are not rerun for this update. Generation class bytes are unchanged from those world-test fixtures. The development version remains **0.0.0**, and the workflow is unchanged.

## Matrix results

| Minecraft | Loader | Build / unit tests | Packaged client | Packaged server | Cached software startup time |
| --- | --- | --- | --- | --- | --- |
| 1.21.1 | Fabric | PASS / 40 | PASS | PASS | 17.98 s |
| 1.21.1 | NeoForge | PASS / 40 | PASS | PASS | 19.1 s |
| 26.1.2 | Fabric | PASS / 40 | PASS | PASS | 17.12 s |
| 26.1.2 | NeoForge | PASS / 40 | PASS | PASS | 18.4 s |
| 26.2 | Fabric | PASS / 40 | PASS | PASS | 17.47 s |
| 26.2 | NeoForge | PASS / 40 | PASS | PASS | 17.66 s |
| 26.3 | Fabric | PASS / 40 | PASS | PASS | 16.61 s |
| 26.3 | NeoForge | PASS / 40 | PASS | PASS | 17.3 s |

There are **320 passing unit-test executions**, with zero failures, errors, or skipped tests, plus **16 successful runtime checks**. The table records the final pass with cached assets and Mesa llvmpipe software rendering, including fixture builds; initial uncached asset downloads take longer. Minecraft 1.21.1 uses Java 21; 26.x uses Java 25. Exact DH/C2ME/loader versions are in `versions.json`.

## Fixes verified

The shared config screen no longer calls `EditBox.setFilter`, which is absent on 26.x Fabric. Apply validates numbers and ranges and displays the existing validation error. Screen navigation uses Minecraft's `gui.setScreen` API on 26.2/26.3 and `setScreen` on earlier targets. On 26.x, Minecraft already extracts the screen background before calling `extractRenderState`; the addon no longer repeats that operation. The startup check reproduced the resulting “Can only blur once per frame” crash before the duplicate call was removed.

These Minecraft signature differences use the existing source preprocessor. Generation, configuration, scheduling, and tests continue sharing one implementation across targets.

## Single config page

All settings are arranged vertically in Minecraft’s standard scrollable list: automatic generation, center mode, X, Z, LOD radius, saved radius, active batches, waiting batches, request grouping, chunks-per-second display and refresh interval. Labels occupy the left column and controls the right. Longer labels wrap on narrow windows. Custom X/Z inputs remain visible and disabled until the custom center is selected. Tooltips and keyboard narration describe each control. The units hint, validation message and three footer buttons remain outside the scroll area.

The existing packaged-client checks now exercise wheel scrolling and click focus on the last text field at a small window size. They also edit coordinates and distance, resize the screen, edit the bottom refresh field, resize back, and verify draft values and the clamped scroll position are retained without changing live configuration. When a larger window fits every setting, its scroll position correctly becomes zero. The input probe uses Minecraft’s mouse-button constants, including the SDL button numbering in 26.3. This catches list clipping and input routing through Minecraft’s two GUI APIs without generating terrain. No additional unit tests or workflow steps are added for this screen-only change.

## Chunks-per-second overlay

`showChunksPerSecond` is an opt-in TOML and in-game toggle. The client HUD displays successful LODgen target chunk completions over a rolling five-second window, including saved pregen targets. Supporting chunks, failed conversions, and normal player/Chunky requests are excluded. Counters are shared by DH/Voxy within a server dimension and removed when the level closes. The HUD is hidden with F1 and on remote servers.

All eight packaged clients force the native action-bar accessor target and check live overlay priority with the toggle both on and off and DH's progress location set to Overlay, Chat, Log and Disabled, restoring original settings afterward. Tiny Voxy-only worlds enable the new display without DH and observe the actual vanilla action-bar text and assert that a completed distant batch contributes a positive throughput value. `chunksPerSecondUpdateIntervalMs` controls HUD refresh frequency (1–60000 ms, default 1000). Cached rates refresh immediately when the interval or server dimension changes, or the HUD toggle is re-enabled after being off. Fake-clock tests cover interval boundaries, live changes, cache resets and idle decay without sleeping. The averaging window remains five seconds. The custom HUD drawing is removed: a client tick sends the same vanilla message used by DH, at the configured interval. Zero-rate or disabled displays clear only LODgen’s own current message. Voxy world checks also verify zero-rate clearing and preservation of another action-bar message. No workflow changes or full-distance performance tests are required.

## Mod Menu support

Fabric builds register an optional `modmenu` entrypoint that opens the existing LODgen config screen and preserves the Mods screen as its parent. Mod Menu is a development dependency, is not bundled, and is not required by the installed addon. All four Fabric targets pass packaged client checks with their pinned Mod Menu versions from `versions.json`: the probe requests LODgen's registered factory through Mod Menu, renders the single settings list, scrolls to its final input, verifies drafts across resizing, and closes the config to return to Mods. Their dedicated-server checks run without Mod Menu. No worlds are opened for these startup checks.

```sh
xvfb-run -a python3 scripts/startup-test.py --loader fabric --modmenu
```

## Earlier Voxy world checks

DH is now optional. LODgen bundles its own relocated NightConfig TOML parser, and a mixin plugin excludes missing renderer integrations before their targets load. DH and Voxy share the native chunk backend, admission gate and ticket cleanup through `ChunkGenerationPipeline`. Voxy alone supplies its own bounded 4×4 tile frontier; block/biome palettes and lighting are copied on the server thread before conversion workers update the client Voxy engine. Renderer shutdown drains conversion before Voxy stops saving. Completed coverage is checkpointed after engine close, and restored on reopening.

| Minecraft / loader | Pinned Voxy setup | Client, generation, shutdown | Reopen / LOD persistence | Target chunks |
| --- | --- | --- | --- | --- |
| 1.21.1 NeoForge | Roxy 0.3.3 + Voxy 0.2.16-beta (1.21.11) | PASS | PASS | 88 |
| 26.1.2 Fabric | Voxy 0.2.18-beta | PASS | PASS | 88 |
| 26.2 Fabric | Voxy 0.2.19-beta | PASS | PASS | 88 |

Each disposable client has **no DH installed**. It renders the config, creates a small single-player world, and enables Voxy automatic generation with a custom center at block X/Z `(16384,-16384)`, LOD radius 1c and saved radius 1c. It completes up to four intersecting 4×4 tiles, and the post-shutdown region-header audit finds exactly the four native targets in the inner saved square, with no supporting native/POI/entity entries outside it. The probe exercises current/origin/start/pause/continue/stop, then uses actual commands for 16 far Overworld chunks and four Nether chunks without visiting the Nether. Both dimensions produce nonempty Voxy sections. A second client process restores completed tile coverage, avoids repeated automatic generation, and checks persistence in both dimensions. No far command chunk, POI or entity region files exist. Normal player/spawn chunks retain their saves. The target cap is 88, including automatic saved-area work; vanilla supporting/spawn generation is additional. Minecraft 26.x uses its dimension-specific Overworld storage path in the audit.

These are real packaged clients with C2ME and Voxy's pinned dependencies from `voxy-versions.json`. Voxy ingestion/storage runs normally; LOD rendering is disabled to keep software rendering tests short. Visual LOD rendering, multiplayer generation, full-distance throughput, and OpenCL performance are outside this pass. Voxy generation intentionally runs only on single-player/LAN hosts with an integrated server. Native remote multiplayer chunks require server-side support outside this addon.

```sh
xvfb-run -a python3 scripts/voxy-test.py --world --reload
```

Roxy's published jar filename is retained in the test launcher: renaming it to `roxy.jar` collides with the `roxy` metadata module it creates in another module layer. Original download bytes are unchanged. Detailed reports and logs remain in `build/<minecraft>/<loader>/voxy-test/` and copied reports accompany the development artifacts.

## Runtime checks

The startup runner uses ordinary packaged loaders and remapped mod jars, not the development client launcher. That avoids DH's embedded Fabric API having intermediary access-widener names in a named development runtime. NeoForge client installations use the official installer; Fabric client installations use its published launcher profile. Minecraft libraries, native libraries, assets, and selected mods are cached under `build/` with artifact checksums verified where published.

The client probe waits for initial resources to load, opens the title screen and Options, presses the actual **LODgen…** button, renders the single list containing all eleven controls, switches to a custom center, enters signed X/Z and a distance draft, resizes to 320×240, scrolls with the mouse wheel, focuses the last input, and verifies draft values and the clamped scroll position survive resizing back. It then closes the config and checks the return to Options. It also loads every generation mixin target through the runtime transformer. The final local pass uses a Linux virtual display and Mesa llvmpipe software rendering without a desktop or GPU. The virtual display stays open between checks to avoid graphics-context resets.

The dedicated-server probe loads LODgen, DH, C2ME, and every generation mixin target. It checks that no `ServerLevel` exists and ends the isolated process before normal server initialization loads world levels. Vanilla bootstrap may create world metadata; no chunk/POI/entity region files may exist. This checks class loading and mixin compatibility, not ticket dispatch, persistence, shutdown, or terrain generation.

Every production class file in each installable jar matches the corresponding packaged startup fixture byte-for-byte. Generation classes also match the retained earlier world-test fixtures; only the config screen and its new list/row classes differ. The fixture adds only test probes and its separate mixin configuration; these are excluded from the installable jars. Current results, class counts, artifact SHA-256 values, and unit-test totals are retained in [dist/validation-lodgen-0.0.0/results.json](dist/validation-lodgen-0.0.0/results.json) and [artifact-audit.json](dist/validation-lodgen-0.0.0/artifact-audit.json). Detailed logs and launcher reports remain in `build/<minecraft>/<loader>/startup/`.

Offline authentication, optional-mod accessor, and unavailable narrator warnings occur in these disposable clients; all required reports and normal client exit checks pass. No original Prism instance files are modified.

## Earlier center, saving and command checks

The config has current-player, world-spawn and custom X/Z centers. Coordinates are blocks; LOD and saved radii are chunks. Saved radius defaults to zero. Fixed-center DH FEATURES generation has its own bounded scheduler, so the selected area generates even when it lies outside the player’s DH viewport. Automatic saved areas also run independently of existing renderer coverage.

All eight targets compile and transform the shared command-registration and server-lifecycle mixins. The 1.21.1 NeoForge task test executes actual Brigadier commands for origin, optional saved radius, block rounding, status, pause, continue and stop. It starts a square 5c task at block `(65536,-65536)`, with saved radius 1c, and closes the server while the task is still running. On restart it resumes automatically and completes all 100 target chunks. A region-header audit confirms exactly four native saves in the inner 2×2 square and no outer target/supporting chunk, POI or entity entries. A separate custom-center automatic DH task then completes four LOD-only targets away from the player, also with no native saves.

Pure tests verify the user’s radius examples (`8190` → 512c, `2040` → 128c), signed coordinates, exact square edges, world-bound clipping, saved-radius zero and larger saved radii, out-of-order completions, pause/stop draining, restart dispatch, and TOML corruption handling. A virtual 64c plan checks all 16,384 targets without generating native terrain. Saving a LOD target retains its generation scope so its structure/feature lookups cannot promote adjacent transient dependencies; a later ordinary request still adopts its dependency footprint normally.

Running tasks checkpoint in the world’s `lodgen/task.toml` and resume after orderly shutdown. Paused tasks remain paused. Stopped/completed tasks do not restart. Active batches drain when paused or stopped. Automatic areas have separate dimension files. An abrupt crash can repeat batches completed since the latest checkpoint. These are correctness checks with small radii, not throughput measurements.

The separate overlap check runs real Chunky (81 chunks) alongside DH FEATURES with C2ME, WWOO 2.6.7, Continents 1.1.14 and their libraries. It verifies lighting, shared requests, ordinary chunk adoption, persisted gold/diamond block edits, and a cold LOD read that leaves an existing native region byte-for-byte unchanged. During initial spawn loading, DH logs a Chunky-accessor initialization error before Chunky is ready; the later concurrent task completes and both persistence audits pass. No OpenCL performance measurement is made.

```sh
python3 scripts/integration-test.py --mc 1.21.1 --loader neoforge --quick --task-check
python3 scripts/integration-test.py --mc 1.21.1 --loader neoforge --quick --chunky
```

## Custom generation distance

`generationDistance = 0` preserves DH's distance. Positive values override the chunk-based FEATURES radius, in chunks. The setting is available in the in-game config and applies live: queued requests beyond the new radius are cancelled while active work drains. Ordinary player and Chunky requests keep their normal generation path.

The small 1.21.1 NeoForge packaged-server test sets custom distance 64 and DH distance 128. A 4×4 section 60 chunks from the target generates valid LOD data; a section 96 chunks away is cancelled before native generation. Applying 0 live allows the second section, and DH remains at 128. Only 32 target chunks generate, and the shutdown audit finds no chunk, POI, or entity region files for the LOD area. Dedicated-server 0 preserves client request ranges; the unit test additionally checks integrated-server 0 follows DH's radius. Whole intersecting sections and supporting native chunks can extend slightly beyond the configured boundary.

```sh
python3 scripts/integration-test.py --mc 1.21.1 --loader neoforge --quick --distance-check
```

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

The startup runner accepts `--skip-build` to reuse its already-built test fixture. Build targets sequentially because Unimined shares remapping data and task history. Full-radius world generation, config Apply in multiplayer, other pack mods, and OpenCL performance are outside this pass. Earlier generation/persistence checks are described in [docs/VALIDATION-0.0.0-initial.md](docs/VALIDATION-0.0.0-initial.md); predecessor performance results remain in [docs/VALIDATION-0.2.1.md](docs/VALIDATION-0.2.1.md).
