# LODgen development validation — 0.0.0

This update adds generation radius, estimated time remaining and status to the existing vanilla action-bar chunks/second display. `/lodgen status` also includes ETA. The version remains **0.0.0**, the config still has nine settings, and the GitHub workflow is unchanged.

## Display and estimates

Example: `LODgen: 1200.0 chunks/s | Radius: 64c | ETA: 12s | Running`.

The radius is in chunks. Commands report their own radius and remaining target count, with throughput from the command's dimension even if the player is elsewhere. Job progress is published as immutable snapshots from the server thread; the client never iterates a live completion tree. Automatic fixed-area DH tasks and saved pregen use the same snapshots.

Automatic Voxy estimates count unfinished 4×4 target tiles inside the current square. Completed tiles from previous centers or outside a reduced radius are excluded. Restored coverage is included. The count matches the generator's partial edge tiles and world-bound clipping, rather than assuming every radius has perfectly aligned 4×4 boundaries. Coverage counts use compact row masks and full-cell totals, avoiding a scan of every completed tile on each update.

Automatic DH estimates use its maintained remaining-generation counter. Pending work without an available estimate remains Running with an unknown ETA. DH's queued-chunk getter is not used because it scans the full waiting map. Progress is read only when the configured action-bar interval elapses.

ETA is remaining targets divided by the five-second rolling chunks/second rate, rounded up. It changes with generation speed, position, distance and completion. Durations show seconds, minutes/seconds, hours/minutes or days/hours. Paused, stopped, waiting, disabled or unavailable-rate estimates show `—`; completed tasks show `0s`.

The message still hides at zero throughput and clears only text that LODgen owns. DH's own overlay retains priority. The existing toggle and update interval control the full message; no extra settings or submenus are added.

## Verification

| Minecraft | Loader | Build / unit tests | Packaged client | Packaged server |
| --- | --- | --- | --- | --- |
| 1.21.1 | Fabric | PASS / 54 | PASS | PASS |
| 1.21.1 | NeoForge | PASS / 54 | PASS | PASS |
| 26.1.2 | Fabric | PASS / 54 | PASS | PASS |
| 26.1.2 | NeoForge | PASS / 54 | PASS | PASS |
| 26.2 | Fabric | PASS / 54 | PASS | PASS |
| 26.2 | NeoForge | PASS / 54 | PASS | PASS |
| 26.3 | Fabric | PASS / 54 | PASS | PASS |
| 26.3 | NeoForge | PASS / 54 | PASS | PASS |

The eight targets execute **432 unit tests**, with zero failures, errors or skipped cases. New tests cover ETA rounding and changing rates, paused/stopped/waiting states, zero/nonfinite rates, duration formatting, duplicate coverage, moved/shrunk radii, full and partial negative-coordinate cells and world edges. Coverage counts are compared against actual frontier enumeration over multiple centers and radii.

All **16 client/server startup checks** load the packaged fixture without opening a world. Clients verify translated chunks/s, radius, ETA and status, DH overlay priority, config-page behavior and the Fabric Mod Menu factory. Production classes match startup fixture classes byte-for-byte, and installable jars exclude test probes. The first 26.1.2 NeoForge attempt encountered an Xvfb GLX `BadAccess` before title-screen checks; a fresh virtual display was used for the final matrix.

Voxy-only startup checks cover **1.21.1 NeoForge/Roxy**, **26.1.2 Fabric**, and **26.2 Fabric**. Only 26.1.2 opens a small world for this update, using at most **88 target chunks**. The check validates the actual vanilla action-bar message, completed automatic coverage/ETA, restored coverage after reopening, a Nether command's own dimension/radius/Complete/zero-ETA snapshot while the player remains in the Overworld, and command status ETA. Existing snapshot/light/mip parity, four inner native saves, zero outer chunk/POI/entity writes, message ownership and hiding at zero remain covered.

The small DH command/restart check uses **5c / 100 targets**, a **1c saved radius / four native saves**, plus four automatic fixed-center targets. It verifies radius and ETA in status, unknown ETA while paused, `0s` after completion, command controls, resumed unfinished work after restart, and exact native region-header saving boundaries.

```sh
python3 scripts/build-all.py
xvfb-run -a python3 scripts/startup-test.py --modmenu
xvfb-run -a python3 scripts/voxy-test.py --mc 1.21.1
xvfb-run -a python3 scripts/voxy-test.py --mc 26.1.2 --world --reload
xvfb-run -a python3 scripts/voxy-test.py --mc 26.2
python3 scripts/integration-test.py --mc 26.1.2 --loader fabric \
  --java /path/to/java25 --run-name regression-progress-tasks \
  --task-check --quick --heap 32G
```

Evidence, artifact hashes and class audits are collected in `dist/validation-lodgen-0.0.0/`. Tests use disposable directories; large throughput runs and other-version world tests are not repeated. The stopped `LODGen-Testing` instance receives the matching 26.1.2 Fabric production jar with a backup outside `mods/`; existing config and C2ME worker settings are retained.

The prior sustained-generation measurements are retained in [docs/VALIDATION-sustained-0.0.0.md](docs/VALIDATION-sustained-0.0.0.md). They describe the preceding build, not a new performance measurement for this display update.
