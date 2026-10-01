# LODgen development validation — 0.0.0

This change is built and checked only on **Minecraft 1.21.1 / NeoForge 21.1.252**. The project, mod ID, packages, mixin config, UI name, and artifact names use LODgen/lodgen. The development version remains **0.0.0**.

## Focused checks

Both the development and self-test builds pass **26 unit tests**, with zero failures or skipped tests. Recent builds take about three to five seconds each on this machine.

The new regression checks exercise:

- TOML defaults, real TOML syntax, strict value types/bounds, atomic replacement, and preservation of unrelated tables. Configuration uses only `lodgen.toml`; earlier addon builds have no migration or compatibility layer.
- A 512-to-128 render-distance change, partial edge sections, square corners, negative coordinates, moving/expanding the view, and world-border coordinates.
- Removal and cancellation of an obsolete waiting future, allowing a fresh request after expansion, and protection of already-dispatched data and replacement tasks at the same position.
- Increasing/decreasing live batch limits and notifying DH only after a batch permit is available, including zero waiting slots.

Existing ownership, adoption, shutdown, cancellation, locality, and feature-scope tests also pass. Unit-test configuration files are isolated under `build/`.

The dispatch guard uses DH's current target position and render radius on each queue selection. It applies to the builtin FEATURES path on the integrated server. It cancels only waiting futures after removing their exact queue entry; active native work drains with its existing ticket/pooled-data ownership. Dedicated-server requests and ordinary Chunky/player chunk generation retain their own ranges.

## Small packaged-server check

The quick fixture uses selected WWOO/Continents jars and Medium DH settings copied from the supplied Create Aeronautics instance: DH 3.3.3, C2ME/OpenCL 0.4.0-alpha.0.122, ScalableLux 0.3.0-alpha.0.8, Chunky 1.4.23, WWOO 2.6.7, Continents 1.1.14, Cristel Lib 3.1.7, and Lithostitched 1.8.0. Java 25, 15 C2ME workers, and Chunky's 768-request limit match the earlier setup.

The fixture generates just four tiles (64 chunks) through **DH's actual WorldGenerationQueue**, actual executor, and LOD database. Warmup is skipped. The fixture applies live limits of one active batch and zero waiting slots, dispatches several requests through the real queue without rejections, then restores the original TOML settings. The surrounding smoke checks exercise overlapping requests, actual concurrent Chunky generation, native lighting snapshots, and normal adoption/player edits. After forced saves, shutdown, and restart, the checks require no DH-only chunk/POI/entity region files and intact saved gold/diamond edits. A cold FEATURES read must leave an existing region file byte-for-byte unchanged.

This workload is a correctness check, **not a throughput benchmark**. No long frontier run or other Minecraft/loader target is tested for this update. The original instance's files are not modified.

The installable development jar's class files match the packaged runtime fixture byte-for-byte. Integration entrypoints, Chunky, C2ME, OpenCL, and worldgen dependencies are excluded. Results and SHA-256 are retained in [dist/validation-lodgen-0.0.0/](dist/validation-lodgen-0.0.0/artifact-audit.json).

## UI and limits

The shared config screen compiles into the development jar. A client-only mixin opens it from **Options → LODgen…** on both loaders; a client-only NeoForge extension opens it from **Mods → LODgen → Config**. Dedicated-server startup confirms the client hooks do not load there. The screen saves `config/lodgen.toml`, publishes an immutable live snapshot, and updates active admission limits. Cancel/defaults remain draft edits until Apply.

The full client UI and an integrated-world settings change are not exercised in this headless check; the dispatch bounds/cancellation regression is covered by the focused tests. Fabric and 26.x builds, LAN/multiplayer client UI, Nether/End generation, upgraded-world blending, and remaining pack mods are outside this validation. Earlier startup messages from DH's optional client hooks and Chunky accessor also occur in this dedicated-server fixture; the checks still complete successfully.

Earlier performance measurements belong to the predecessor: [docs/VALIDATION-0.2.1.md](docs/VALIDATION-0.2.1.md).

## Reproduce quickly

```sh
./gradlew -PmcVersion=1.21.1 -Ploader=neoforge build
python3 scripts/integration-test.py --mc 1.21.1 --loader neoforge --quick \
  --opencl --chunky --benchmark 8 --dh-queue --dh-executor --store-lods \
  --native-workers 15 --chunky-working-count 768 \
  --worldgen-instance '/path/to/Prism/instance/minecraft'
```

Use Java 25 for OpenCL. `--skip-build` reuses an existing self-test jar. `--quick` skips warmup, reuses the cached versioned NeoForge installation, and caps the main/restart checks at 120/60 seconds. Logs and persistence reports remain in `build/1.21.1/neoforge/selftest/packaged-server/`.
