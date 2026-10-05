# Voxy save race — 2026-10-05

The LODGen-Testing instance's latest log records `Section freed while marked as dirty or in the save queue: dirty,` at 01:09:53 on Voxy's mesh worker. Its task checkpoint remains RUNNING with no task error. The log stops after the player disconnects at 01:10:17, before orderly server shutdown completes.

The installed Voxy 0.2.16 saver clears the dirty flag before clearing the save-queue flag. A concurrent update between those operations can see the old queue flag, skip another save, and leave a dirty section without a save reference. Voxy's section tracker throws while holding its write lock; subsequent ingestion can then block on that tracker. LODgen's shutdown waits for active ingestion, so this failure also explains a plausible path to both stalled generation and the reported exit freeze. There was no live frozen JVM to obtain a thread dump from.

LODgen now backports the queue-before-dirty ordering in the optional `VoxySavingMixin` plugin hook. It moves the old instruction sequence only when its exact shape matches, preserves the original storage and reference-release behavior, and leaves already-fixed savers untouched. Voxy's [current saver implementation](https://github.com/MCRcortex/voxy/blob/dev/src/main/java/me/cortex/voxy/common/world/service/SectionSavingService.java) also clears the queue first. Version remains 0.0.0.

## Verification

- All eight production targets build and pass 77 unit tests each: **616 executions**, zero failures, errors, or skips.
- Four regression tests reproduce the original dirty-section failure, execute repaired bytecode to verify that the concurrent update retains a save reference and persists, check idempotence/already-fixed jars, and reject unsafe instruction shapes before changing them.
- The actual installed Voxy jar and Roxy's generated patched jar both accept the repair and pass ASM bytecode verification. The pinned 26.1.2 and 26.2 Voxy jars already have the corrected ordering and remain unmodified. These checks do not load Minecraft or create a window.
- Packaged headless shutdown checks pass on **1.21.1 NeoForge** and **26.2 Fabric**, with C2ME, OpenCL, two native workers, CPU load 5, and 64 active Empty-terrain targets. Each creates a real temporary directory, shuts down before its native request completes, verifies that the directory is removed after shutdown, and rejects native region/POI/entity files in the far LOD area.

```sh
python3 scripts/build-all.py
python3 scripts/integration-test.py --mc 1.21.1 --loader neoforge --quick --shutdown-check --opencl --java /usr/lib/jvm/java-25-openjdk/bin/java --native-workers 2 --cpu-load 5 --run-name shutdown-opencl
python3 scripts/integration-test.py --mc 26.2 --loader fabric --quick --shutdown-check --opencl --java /usr/lib/jvm/java-25-openjdk/bin/java --native-workers 2 --cpu-load 5 --run-name shutdown-opencl
```

The NeoForge production jar was installed in LODGen-Testing after validation; SHA-256 is `ebe9228fa9a76db804bb18e6ee9899fe70b3f86772fbf305fb09f5d279399b3d`. The original jar and diagnostic files are preserved in the instance's `minecraft/lodgen-backups/crash-fix-20261005-012332/`. Saved worlds, task progress, and configuration were not changed.

Evidence is collected in `dist/validation-voxy-save-race-0.0.0/`. No check launches a client window. Actual Voxy rendering and the user's world-exit flow remain to be confirmed after restarting the instance.
