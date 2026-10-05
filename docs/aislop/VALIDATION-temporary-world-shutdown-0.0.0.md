# Temporary LOD world shutdown — 2026-10-05

The LODGen-Testing log stops during world exit at 01:46:28. Its temporary Empty-terrain world's storage and OpenCL resources close before C2ME has finished unloading its chunk holders, followed by cancelled storage reads. The server never logs its normal `Stopping server` message. No frozen game JVM remained available for a thread dump.

Temporary LOD worlds are deliberately absent from MinecraftServer's dimension list, so its normal shutdown loop does not drain them. LODgen now removes their closing tickets, ticks their chunk sources and pumps server tasks until `ChunkMap.hasWork()` is false before closing the level. Persistence suppression and terrain settings remain registered during the drain. The implementation uses the matching ticket shutdown operation for each supported Minecraft version; version remains 0.0.0.

The shutdown fixture now requires admitted native holders before stopping and rejects holders remaining after storage closure. With the original implementation it reports `FAIL: temporary LOD chunk holders survived storage closure`; with the fix it passes. The previous directory-only assertion could miss this failure.

## Verification

- All eight production targets build and pass 77 unit tests each: **616 executions**, zero failures, errors or skips.
- Packaged headless shutdown checks pass on 1.21.1 NeoForge and 26.2 Fabric with C2ME, OpenCL and 64 active Empty-terrain targets. Temporary holders and directories are removed; distant native, POI and entity saves remain absent.
- The NeoForge check also passes with the instance's actual BloomingNature, Biolith and Architectury jars, CPU load 5 and 32 C2ME workers.
- A 1.21.1 NeoForge Empty-terrain command/reopen check passes with those worldgen mods, OpenCL and 32 workers. An unfinished RUNNING 5c task resumes after shutdown, completes its 100 targets, saves exactly four intended native chunks, and leaves no native, POI or entity entries outside the saved area.

```sh
python3 scripts/build-all.py
python3 scripts/integration-test.py --mc 1.21.1 --loader neoforge --quick --shutdown-check --opencl --java /usr/lib/jvm/java-25-openjdk/bin/java --native-workers 32 --cpu-load 5 --run-name shutdown-regression
python3 scripts/integration-test.py --mc 26.2 --loader fabric --quick --shutdown-check --opencl --java /usr/lib/jvm/java-25-openjdk/bin/java --native-workers 2 --cpu-load 5 --run-name shutdown-regression
python3 scripts/integration-test.py --mc 1.21.1 --loader neoforge --quick --task-check --cave-mode empty --opencl --java /usr/lib/jvm/java-25-openjdk/bin/java --native-workers 32 --run-name shutdown-task-reopen
```

For the instance-matched NeoForge runs, each of the three worldgen/library jars above was supplied with `--extra-mod`. Evidence, complete server logs, reports and installation hashes are collected in `dist/validation-temporary-world-shutdown-0.0.0/`.

The production NeoForge jar was installed into LODGen-Testing; SHA-256 is `84b96161852ba8c7f1f3549141b586217ac2dc291b4324f248e542284fefe005`. The previous jar, logs, configuration and task checkpoint are backed up in `minecraft/lodgen-backups/save-exit-fix-20261005-015854/`. Hashes confirm all 101 instance configuration/world files are unchanged. No check launches a client window; the user's integrated-server exit flow still needs confirmation after starting the patched instance.
