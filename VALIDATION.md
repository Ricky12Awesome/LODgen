# LODgen development validation — 0.0.0

Autostart now creates a real generation task through the same job, square plan, native pipeline and checkpoint used by `/lodgen start`. DH and Voxy no longer have separate LODgen automatic schedulers. The version remains **0.0.0**, and the GitHub workflow is unchanged.

## Task behavior

Automatic tasks capture the occupied dimension and configured center, generation radius and saved radius. Current position is captured on start. `/lodgen status`, `pause`, `continue`, `stop` and `cancel` control the actual job; `cancel` aliases `stop`. Pausing lets active native batches drain and retains PAUSED even if they finish the area. A stopped task cannot restart from login, settings changes or completion callbacks.

Both origins persist in `<world>/lodgen/task.toml`, using checkpoint layout **3**. Earlier development task/area/tile checkpoints are not imported. Running tasks resume, paused tasks remain paused, and stopped/completed tasks retain their state. An explicit start can replace an automatic task and still runs with automatic generation or DH generation disabled.

Automatic tasks obey the addon toggle and DH's generator plan/mode. Surface plans complete DH's rough pass before native chunks. DH's normal rough viewport remains available while an automatic task owns the native chunk phase. A paused/stopped task blocks DH's automatic queue too; normal player and Chunky chunk generation retain their own path. Turning the addon toggle off releases DH's normal chunk phase as before.

The shared plan preserves completed inner patch rings when the distance shrinks, without enumerating chunk data or allocating a world-sized plan. Increasing the saved area or moving its center requires work. Voxy now supplies renderer conversion and shutdown handling; generation progress and resume state come from the task checkpoint.

## Verification

All eight production targets build and pass **54 unit tests each** (432 executions): Fabric and NeoForge for 1.21.1, 26.1.2, 26.2 and 26.3. There are no failed, erroneous or skipped cases. New coverage checks automatic task origin/checkpoint intent, a pause retained during active completion, and completed inner-radius reuse while the outer job is unfinished. Tests for the removed Voxy frontier/tile checkpoint were removed with that code.

The existing packaged server integration and reopen commands also passed on all eight targets with fresh DH settings and two CPUs exposed during this change. They check actual LOD output, lighting, no-save behavior, normal FULL saves/player edits and persistence after reopening. Final focused world checks below run on 26.2 Fabric using the current source.

| Headless check | Workload | Result |
| --- | --- | --- |
| Autostart / reopen | 5c, 100 targets; 1c saved radius; four additional LOD targets | PASS: real task creation, pause/drain, restored pause with no dispatch, continue, completed-radius shrink, cancel and DH queue blocking |
| Command / reopen | 5c, 100 targets; 1c saved radius; four additional LOD targets | PASS: command controls, radius rounding, running-task resume, saved-radius persistence and automatic task creation after settings changes |
| DH plans | At most 5c; 156 native LOD targets | PASS: rough/chunk phases, addon toggle, center-first selection, disabled drain/re-enable, commands with DH disabled and fixed rough-before-chunk generation |

Both task checks save exactly **four native chunks**, with no native, POI or entity entries outside their saved area. Client test helpers compile for 1.21.1 NeoForge and 26.2 Fabric. No test launches a window; actual Voxy client rendering and large performance runs were not repeated.

```sh
python3 scripts/build-all.py
python3 scripts/integration-test.py --mc 26.2 --loader fabric --quick --autostart-check
python3 scripts/integration-test.py --mc 26.2 --loader fabric --quick --task-check
python3 scripts/integration-test.py --mc 26.2 --loader fabric --quick --dh-plan-check
```

Evidence and artifact hashes are collected in `dist/validation-autostart-0.0.0/`. All worlds are disposable and small. Previous checks are retained in [docs/VALIDATION-ci-admission-0.0.0.md](docs/VALIDATION-ci-admission-0.0.0.md); prior sustained-generation measurements remain in [docs/VALIDATION-sustained-0.0.0.md](docs/VALIDATION-sustained-0.0.0.md). This update makes no new performance claim.

## WWOO performance — 2026-10-02

Disk target compilation, native palette rejection and API-generator task ownership are covered in [docs/VALIDATION-wwoo-0.0.0.md](docs/VALIDATION-wwoo-0.0.0.md). Repeated 16,384-chunk WWOO/OpenCL runs measure **499–567 cps**, versus **455–467 cps** with original disk evaluation; the arithmetic means improve about **15%**. The rate remains below the reported Terralith/Tectonic 800–1,200 cps.

All eight production builds pass 54 unit tests each, and all eight headless packaged generation/reopen checks pass with live predicate probes. The final WWOO check verifies **189,427,022** target results, including all positions in **75,234** skipped disks. DH API-generator plan tests pass both alone and with the instance's actual SeedGen 0.8.0 jar. Evidence and artifact hashes are in `dist/validation-wwoo-0.0.0/`.
