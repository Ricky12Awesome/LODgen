#!/usr/bin/env python3
"""Run the packaged mod with real DH and C2ME in a disposable, isolated server."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import shutil
import subprocess
import urllib.request

from script_utils import ROOT, load_json, read_mod_version

matrix = load_json("versions.json")
mod_version = read_mod_version()
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--mc", choices=matrix, required=True)
parser.add_argument("--loader", choices=("fabric", "neoforge"), required=True)
parser.add_argument("--java", default="java", help="Java 21 for MC 1.21.1; Java 25 for MC 26.x")
parser.add_argument("--run-name", help="Separate disposable benchmark directory name")
parser.add_argument("--skip-build", action="store_true")
parser.add_argument("--startup-only", action="store_true", help="Load the packaged startup fixture and stop before opening a world")
parser.add_argument("--minimal", action="store_true", help="With --startup-only, install LODgen without DH, C2ME or Fabric API")
parser.add_argument("--task-check", action="store_true", help="Commands, mixed saved/LOD radius, and auto resume using only a 5c radius")
parser.add_argument("--autostart-check", action="store_true", help="Dedicated autostart blocking and auto-resume setting lifecycle using only a 5c radius")
parser.add_argument("--shutdown-check", action="store_true", help="Quit with 64 native Empty LOD chunks still generating")
parser.add_argument("--dh-plan-check", action="store_true", help="DH generator plans, center-first ordering, live disable and commands; maximum 5c radius")
parser.add_argument("--distance-check", action="store_true", help="Check custom 64 versus DH 128 using only two 4x4 LOD sections")
parser.add_argument("--quick", action="store_true", help="Skip benchmark warmup and cap server checks at 120/60 seconds")
parser.add_argument("--opencl", action="store_true", help="Install the optional C2ME OpenCL addon and ScalableLux; requires Java 25")
parser.add_argument("--chunky", action="store_true", help="Install Chunky and exercise a real concurrent pregen task")
parser.add_argument("--benchmark", type=int, default=0, help="Generate an N by N chunk benchmark area (N divisible by 4)")
parser.add_argument("--warmup-axis", type=int, default=16, help="Separate warmup area width in chunks")
parser.add_argument("--layout", choices=("row", "radial"), default="row", help="Order DH requests by rows or distance from the area's center")
parser.add_argument("--workers", type=int, default=8, help="Concurrent DH benchmark requests")
parser.add_argument("--native-workers", type=int, default=0, help="Override C2ME parallelism in the disposable server (0 keeps its default)")
parser.add_argument("--chunky-working-count", type=int, default=0, help="Override Chunky's in-flight limit in the disposable server")
parser.add_argument("--cpu-load", type=int, choices=range(1, 6), default=3, help="LODgen CPU load, also overriding DH in the disposable server")
parser.add_argument("--dh-executor", action="store_true", help="Use DH's real worldgen executor for the benchmark")
parser.add_argument("--dh-queue", action="store_true", help="Use DH's actual request selection and admission queue")
parser.add_argument("--frontier-radius", type=int, default=0, help="Start DH queue requests on a distant square frontier, in chunks")
parser.add_argument("--store-lods", action="store_true", help="Include DH's actual asynchronous database updates")
parser.add_argument("--worldgen-instance", type=Path, help="Copy WWOO, Continents, their libraries and DH/C2ME settings from a Prism Minecraft directory")
parser.add_argument("--instance-optimizations", action="store_true", help="Copy the instance's server-safe optimization mods and use ZGC/compact headers")
parser.add_argument("--extra-mod", type=Path, action="append", default=[], help="Copy an additional server-compatible mod into the disposable test")
parser.add_argument("--original-predicates", action="store_true", help="Benchmark vanilla disk predicates with the same LODgen pipeline")
parser.add_argument("--original-ore-allocations", action="store_true", help="Benchmark vanilla ore readers/iterators with the same LODgen pipeline")
parser.add_argument("--verify-terrain", action="store_true", help="Hash all benchmark LOD columns, including blocks, biomes and lighting")
parser.add_argument("--cave-mode", choices=("generate", "fill", "empty"), default="generate", help="LOD-only cave mode in the disposable test")
parser.add_argument("--cave-check", action="store_true", help="Compare surface layers, deep interior and saved chunk isolation across all three cave modes")
parser.add_argument("--cave-center", nargs=2, type=int, metavar=("CHUNK_X", "CHUNK_Z"), help="Choose another terrain area for --cave-check")
parser.add_argument("--verify-predicates", action="store_true", help="Compare every optimized disk test against vanilla on the same live terrain")
parser.add_argument("--heap", default="8G", help="Maximum heap for world tests")
parser.add_argument("--optimized", action="store_true", help="26.2 Fabric optimization stack, ZGC and compact object headers")
parser.add_argument("--jfr", action="store_true", help="Record the disposable server with Java Flight Recorder")
parser.add_argument("--trace-ownership", action="store_true", help="Log the callers of transient chunk adoption")
parser.add_argument("--vanilla", action="store_true", help="Test without C2ME")
parser.add_argument("--baseline", action="store_true", help="Run the same check with DH's original FEATURES generator")
parser.add_argument("--vss-check", action="store_true", help="Dedicated-server Voxy Server Side generation gate and transient LOD output check (1.21.1 NeoForge)")
parser.add_argument("--vss-generation", action=argparse.BooleanOptionalAction, default=True,
                    help="Initial VSS generation.enabled setting for --vss-check (default: true; use --no-vss-generation to test false)")
parser.add_argument("--vss-store", action=argparse.BooleanOptionalAction, default=True,
                    help="Enable VSS's native LOD store for --vss-check (default: true; use --no-vss-store to test LODgen sidecar alone)")
args = parser.parse_args()
if args.vss_check and (args.mc != "1.21.1" or args.loader != "neoforge"):
    parser.error("--vss-check requires --mc 1.21.1 --loader neoforge")
if args.vss_check and (args.startup_only or args.task_check or args.autostart_check or args.shutdown_check
                       or args.dh_plan_check or args.distance_check or args.cave_check or args.benchmark
                       or args.baseline or args.opencl or args.chunky or args.worldgen_instance
                       or args.instance_optimizations or args.extra_mod or args.original_predicates
                       or args.original_ore_allocations or args.verify_terrain or args.verify_predicates
                       or args.optimized or args.vanilla):
    parser.error("--vss-check is a standalone packaged-server check")
if args.shutdown_check and (args.startup_only or args.task_check or args.autostart_check or args.dh_plan_check or args.cave_check or args.distance_check or args.benchmark or args.baseline):
    parser.error("--shutdown-check cannot be combined with other check modes")
if args.cave_check and (args.startup_only or args.task_check or args.dh_plan_check or args.distance_check or args.benchmark or args.baseline):
    parser.error("--cave-check runs all three modes and cannot be combined with other check modes")
if args.cave_center and not args.cave_check:
    parser.error("--cave-center requires --cave-check")
if args.instance_optimizations and not args.worldgen_instance:
    parser.error("--instance-optimizations requires --worldgen-instance")
if args.run_name and not re.fullmatch(r"[A-Za-z0-9_-]+", args.run_name):
    parser.error("--run-name must be a simple directory name")
if args.optimized and (args.mc != "26.2" or args.loader != "fabric"):
    parser.error("--optimized currently pins the 26.2 Fabric benchmark stack")
if args.autostart_check:
    args.task_check = True
if args.task_check and (args.startup_only or args.distance_check or args.benchmark or args.baseline):
    parser.error("--task-check cannot be combined with startup-only, distance-check, benchmark or baseline")
if args.dh_plan_check and (args.startup_only or args.task_check or args.distance_check or args.benchmark or args.baseline):
    parser.error("--dh-plan-check cannot be combined with startup-only, task-check, distance-check, benchmark or baseline")
if args.distance_check and (args.startup_only or args.benchmark or args.baseline):
    parser.error("--distance-check cannot be combined with startup-only, benchmark or baseline")
if args.minimal and not args.startup_only:
    parser.error("--minimal requires --startup-only")
if args.minimal:
    args.vanilla = True
if args.startup_only and (args.opencl or args.chunky or args.benchmark or args.worldgen_instance or args.baseline):
    parser.error("--startup-only cannot be combined with worldgen or benchmark options")
if args.vanilla and args.opencl:
    parser.error("--opencl requires C2ME; omit --vanilla")
if args.warmup_axis < 4 or args.warmup_axis % 4:
    parser.error("--warmup-axis must be a positive multiple of 4")
if args.benchmark < 0 or args.benchmark % 4:
    parser.error("--benchmark must be zero or a positive multiple of 4")
if not 1 <= args.workers <= 1024:
    parser.error("--workers must be between 1 and 1024")
if args.frontier_radius < 0 or args.frontier_radius % 4:
    parser.error("--frontier-radius must be a nonnegative multiple of 4")
if args.frontier_radius and not args.dh_queue:
    parser.error("--frontier-radius requires --dh-queue")
if args.native_workers < 0 or args.chunky_working_count < 0:
    parser.error("Worker count overrides must be nonnegative")
target = matrix[args.mc]
base = ROOT / "build" / args.mc / args.loader / ("startup" if args.startup_only else "selftest")
run = base / (args.run_name or ("packaged-server-vss" if args.vss_check else
                              "packaged-server" + ("-tasks" if args.task_check else "") + ("-baseline" if args.baseline else "") + ("-vanilla" if args.vanilla else "")))
run.mkdir(parents=True, exist_ok=True)
# Never reuse worlds: previous normal chunks would hide disk-write regressions.
world = run / "world"
if world.exists():
    shutil.rmtree(world)
report = run / ("startup-result.txt" if args.startup_only else "integration-result.txt")
report.unlink(missing_ok=True)
(run / "benchmark-result.json").unlink(missing_ok=True)


def download(url, path, expected=None):
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(url, timeout=60) as response, path.open("wb") as output:
            shutil.copyfileobj(response, output)
    if expected and hashlib.sha512(path.read_bytes()).hexdigest() != expected:
        path.unlink()
        raise SystemExit(f"Checksum mismatch: {path}")


def modrinth(version, name):
    cached = ROOT / "build" / "test-mod-cache" / f"{version}.jar"
    # Version IDs are immutable; downloads are checksum-verified below. Reuse
    # them without making every repeat depend on Modrinth being reachable.
    if cached.exists():
        shutil.copyfile(cached, run / "mods" / f"{name}.jar")
        return
    with urllib.request.urlopen(f"https://api.modrinth.com/v2/version/{version}", timeout=60) as response:
        metadata = json.load(response)
    artifact = next(f for f in metadata["files"] if f["primary"])
    download(artifact["url"], cached, artifact["hashes"]["sha512"])
    shutil.copyfile(cached, run / "mods" / f"{name}.jar")


if not args.skip_build:
    subprocess.run([str(ROOT / "gradlew"), f"-PmcVersion={args.mc}", f"-Ploader={args.loader}",
                    "-PstartupTest=true" if args.startup_only else "-PselfTest=true", "build"], cwd=ROOT, check=True)
artifacts = [p for p in (base / "libs").glob("*.jar") if p.name.endswith(f"-{mod_version}.jar")]
if len(artifacts) != 1:
    raise SystemExit("Build the self-test variant first")
shutil.rmtree(run / "mods", ignore_errors=True)
(run / "mods").mkdir()
shutil.copyfile(artifacts[0], run / "mods" / "lodgen-test.jar")
if args.vss_check:
    modrinth("ysVSK5wH", "lss")
elif not args.minimal:
    modrinth(target["dh"], "distanthorizons")
if not args.vss_check and not args.vanilla:
    modrinth(target["c2meFabric" if args.loader == "fabric" else "c2meNeoForge"], "c2me")
else:
    (run / "mods" / "c2me.jar").unlink(missing_ok=True)
for optional in ("c2me-ocl", "scalablelux", "chunky"):
    (run / "mods" / f"{optional}.jar").unlink(missing_ok=True)
if args.opencl or args.chunky or args.optimized:
    tests = load_json("test-versions.json")
    for project in ((["c2me-ocl", "scalablelux"] if args.opencl else []) + (["chunky"] if args.chunky else []) + (["lithium", "ferritecore", "structure-layout-optimizer", "resourceful-config", "zfastnoise"] if args.optimized else [])):
        version = tests[project][args.mc][args.loader]
        if not version:
            raise SystemExit(f"No published {project} build for {args.mc} {args.loader}")
        modrinth(version, project)
(run / "eula.txt").write_text("eula=true\n")
(run / "config").mkdir(exist_ok=True)
if args.worldgen_instance:
    for pattern in ("wwoo-*.jar", "Continents_*.jar", "cristellib-*.jar", "lithostitched-*.jar"):
        for artifact in (args.worldgen_instance / "mods").glob(pattern):
            shutil.copyfile(artifact, run / "mods" / artifact.name)
    for config in ("DistantHorizons.toml", "c2me.toml"):
        shutil.copyfile(args.worldgen_instance / "config" / config, run / "config" / config)
    lithostitched_config = args.worldgen_instance / "config" / "lithostitched.json"
    if lithostitched_config.exists():
        shutil.copyfile(lithostitched_config, run / "config" / lithostitched_config.name)
    for name in ("wwoo", "cristellib"):
        source = args.worldgen_instance / "config" / name
        if source.exists():
            shutil.copytree(source, run / "config" / name, dirs_exist_ok=True)
    if args.instance_optimizations:
        for pattern in ("lithium-*.jar", "ferritecore-*.jar", "structure_layout_optimizer-*.jar",
                        "ResourcefulConfig-*.jar", "zfastnoise-*.jar", "zconfig-*.jar"):
            for artifact in (args.worldgen_instance / "mods").glob(pattern):
                shutil.copyfile(artifact, run / "mods" / artifact.name)
        for name in ("zfastnoise.mixin.toml", "lithium.properties", "ferritecore.mixin.properties"):
            source = args.worldgen_instance / "config" / name
            if source.exists():
                shutil.copyfile(source, run / "config" / name)
for artifact in args.extra_mod:
    if not artifact.is_file() or artifact.suffix != ".jar":
        parser.error(f"--extra-mod needs an existing .jar: {artifact}")
    shutil.copyfile(artifact, run / "mods" / artifact.name)
if args.native_workers:
    c2me_config = run / "config" / "c2me.toml"
    current = c2me_config.read_text() if c2me_config.exists() else "version = 3\nglobalExecutorParallelism = \"default\"\n"
    c2me_config.write_text(re.sub(r"(?m)^globalExecutorParallelism\s*=.*$", f"globalExecutorParallelism = {args.native_workers}", current))
(run / "config" / "lodgen.toml").write_text(f"enabled={'false' if args.baseline else 'true'}\ncpuLoad={args.cpu_load}\ncaveMode=\"{args.cave_mode}\"\n")
vss_config = run / "config" / "vss-server-config.yaml"
vss_config_before = None
if args.vss_check:
    # Keep the VSS service on while pinning generation and native-store modes.
    # The server check and post-run byte comparison catch accidental config edits.
    vss_config.write_text("config_version: 1\nservice:\n  enabled: true\ngeneration:\n  enabled: "
                          + ("true" if args.vss_generation else "false")
                          + "\n  concurrency:\n    global: 1\n    per_player: 1\nstorage:\n  lod_store:\n    enabled: "
                          + ("true" if args.vss_store else "false")
                          + "\n    backfill:\n      enabled: false\n")
    vss_config_before = vss_config.read_bytes()
(run / "server.properties").write_text("online-mode=false\nserver-port=0\nlevel-seed=123456789\n"
                                       "view-distance=2\nsimulation-distance=2\nmax-tick-time=180000\n")
heap = "-Xmx2G" if args.startup_only else "-Xmx" + args.heap
if args.loader == "fabric":
    version = target["fabricApi"]
    if not args.minimal:
        download(f"https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/{version}/fabric-api-{version}.jar",
                 run / "mods" / "fabric-api.jar")
    download(f"https://meta.fabricmc.net/v2/versions/loader/{args.mc}/{target['fabricLoader']}/1.1.1/server/jar",
             run / "fabric-server-launch.jar")
    command = [args.java, heap, "-Dlodgen.test.chunky=" + str(args.chunky).lower(), "-Dlodgen.test.benchmark=" + str(args.benchmark), "-jar", "fabric-server-launch.jar", "nogui"]
else:
    nf = target["neoForge"]
    installer = run / f"neoforge-{nf}-installer.jar"
    download(f"https://maven.neoforged.net/releases/net/neoforged/neoforge/{nf}/neoforge-{nf}-installer.jar",
             installer)
    argument_file = run / "libraries" / "net" / "neoforged" / "neoforge" / nf / "unix_args.txt"
    cached_libraries = ROOT / "build" / args.mc / args.loader / "selftest/packaged-server/libraries"
    if (args.startup_only or args.task_check or args.cave_check) and not argument_file.exists() and (cached_libraries / "net" / "neoforged" / "neoforge" / nf / "unix_args.txt").exists():
        shutil.copytree(cached_libraries, run / "libraries", dirs_exist_ok=True)
    if not argument_file.exists():
        subprocess.run([args.java, "-jar", str(installer), "--installServer"], cwd=run, check=True)
    if argument_file.exists():
        command = [args.java, heap, "-Dlodgen.test.chunky=" + str(args.chunky).lower(), "-Dlodgen.test.benchmark=" + str(args.benchmark), "@" + str(argument_file), "nogui"]
    else:
        launchers = list(run.glob("neoforge-*-server.jar"))
        if len(launchers) != 1:
            raise SystemExit("NeoForge installer did not produce a recognized server launcher")
        command = [args.java, heap, "-Dlodgen.test.chunky=" + str(args.chunky).lower(), "-Dlodgen.test.benchmark=" + str(args.benchmark), "-jar", str(launchers[0]), "nogui"]

benchmark_options = [f"-Dlodgen.test.warmupAxis={args.warmup_axis}", f"-Dlodgen.test.layout={args.layout}", f"-Dlodgen.test.workers={args.workers}",
                     f"-Dlodgen.test.baseline={str(args.baseline).lower()}",
                     f"-Dlodgen.test.distance={str(args.distance_check).lower()}",
                     f"-Dlodgen.test.tasks={str(args.task_check).lower()}",
                     f"-Dlodgen.test.shutdown={str(args.shutdown_check).lower()}",
                     f"-Dlodgen.test.autostart={str(args.autostart_check).lower()}",
                     f"-Dlodgen.test.dhPlans={str(args.dh_plan_check).lower()}",
                     f"-Dlodgen.test.skipWarmup={str(args.quick).lower()}",
                     f"-Dlodgen.test.nativeWorkers={args.native_workers}",
                     f"-Dlodgen.test.dhExecutor={str(args.dh_executor).lower()}",
                     f"-Dlodgen.test.dhQueue={str(args.dh_queue).lower()}",
                     f"-Dlodgen.test.frontierRadius={args.frontier_radius}",
                     f"-Dlodgen.test.storeLods={str(args.store_lods).lower()}"]
benchmark_options.append(f"-Dlodgen.test.vss={str(args.vss_check).lower()}")
benchmark_options.append(f"-Dlodgen.test.vssGeneration={str(args.vss_generation).lower()}")
benchmark_options += [f"-Dlodgen.test.originalPredicates={str(args.original_predicates).lower()}",
                      f"-Dlodgen.test.caves={str(args.cave_check).lower()}",
                      f"-Dlodgen.test.originalOreAllocations={str(args.original_ore_allocations).lower()}",
                      f"-Dlodgen.test.verifyPredicates={str(args.verify_predicates).lower()}",
                      f"-Dlodgen.test.verifyTerrain={str(args.verify_terrain).lower()}"]
if args.trace_ownership:
    benchmark_options.append("-Dlodgen.test.traceOwnership=true")
if args.cave_center:
    benchmark_options += [f"-Dlodgen.test.caveX={args.cave_center[0]}", f"-Dlodgen.test.caveZ={args.cave_center[1]}"]
if args.chunky_working_count:
    benchmark_options.append(f"-Dchunky.maxWorkingCount={args.chunky_working_count}")
if args.optimized or args.instance_optimizations:
    benchmark_options += ["-XX:+UseZGC", "-XX:+UseCompactObjectHeaders"]
if args.jfr:
    benchmark_options.append("-XX:StartFlightRecording=filename=benchmark.jfr,settings=profile,dumponexit=true")
command[1:1] = benchmark_options
log = run / "integration-server.log"
print(f"Testing packaged {args.mc} {args.loader}; log: {log}", flush=True)
with log.open("w") as output:
    subprocess.run(command, cwd=run, stdout=output, stderr=subprocess.STDOUT,
                   timeout=300 if args.vss_check else 120 if args.quick or args.startup_only else 900, check=True)
if not report.exists() or not report.read_text().startswith("PASS:"):
    print(log.read_text()[-16000:])
    raise SystemExit(report.read_text() if report.exists() else "Server stopped without an integration result")
if args.vss_check:
    if vss_config.read_bytes() != vss_config_before:
        raise SystemExit("VSS config bytes changed during the LODgen integration check")
    first_log_text = log.read_text()
    warning = "§cLODgen has disabled VSS generation, set §b'generation.enabled'§c to §dfalse§c in §bconfig/vss-server-config.yaml§con sever to get rid of this warning"
    plain_warning = re.sub(r"§[0-9a-fk-or]", "", warning, flags=re.IGNORECASE)
    occurrences = first_log_text.count(plain_warning)
    if occurrences != (1 if args.vss_generation else 0):
        raise SystemExit(f"VSS warning count was {occurrences}; expected {1 if args.vss_generation else 0}\n{first_log_text[-6000:]}")
    if not args.vss_generation and "LODgen has disabled VSS generation" in first_log_text:
        raise SystemExit("VSS warning appeared while generation.enabled was false")
    print(report.read_text().strip())
    reload_report = run / "integration-reload-result.txt"
    reload_report.unlink(missing_ok=True)
    reload_command = command[:1] + ["-Dlodgen.test.vssReload=true"] + command[1:]
    reload_log = run / "integration-reload.log"
    with reload_log.open("w") as output:
        subprocess.run(reload_command, cwd=run, stdout=output, stderr=subprocess.STDOUT, timeout=180, check=True)
    # VssCheck writes the normal report path on both launches; retain the first
    # generation result above and use the reloaded process log/report below.
    if not report.exists() or not report.read_text().startswith("PASS:"):
        print(reload_log.read_text()[-10000:])
        raise SystemExit(report.read_text() if report.exists() else "VSS reload stopped without an integration result")
    if vss_config.read_bytes() != vss_config_before:
        raise SystemExit("VSS config bytes changed during the LODgen reload check")
    reload_text = reload_log.read_text()
    occurrences = reload_text.count(plain_warning)
    if occurrences != (1 if args.vss_generation else 0):
        raise SystemExit(f"VSS reload warning count was {occurrences}; expected {1 if args.vss_generation else 0}\n{reload_text[-6000:]}")
    if not args.vss_generation and "LODgen has disabled VSS generation" in reload_text:
        raise SystemExit("VSS warning appeared during reload while generation.enabled was false")
    for dimension_root in (world, world / "DIM-1"):
        for folder in ("region", "poi", "entities"):
            for region in (dimension_root / folder).glob("r.*.*.mca"):
                _, rx, rz, _ = region.name.split(".")
                if abs(int(rx) - 128) <= 1 and abs(int(rz) + 128) <= 1:
                    raise SystemExit(f"VSS LOD-only area wrote native {folder} data: {region}")
    print(report.read_text().strip())
    raise SystemExit(0)
if args.autostart_check:
    print(report.read_text().strip())
    raise SystemExit(0)
if args.startup_only:
    if any(world.rglob("*.mca")):
        raise SystemExit("Startup-only check generated chunks")
    print(report.read_text().strip())
    raise SystemExit(0)
if args.shutdown_check:
    for region in world.rglob("r.*.*.mca"):
        _, rx, rz, _ = region.name.split(".")
        if 190 <= int(rx) <= 194 and -194 <= int(rz) <= -190:
            raise SystemExit(f"Shutdown-check LOD area was saved: {region}")
    print(report.read_text().strip())
    raise SystemExit(0)
if args.cave_check:
    saved_targets = {(x, z) for x in (8191, 8192) for z in (-8193, -8192)}
    for region in world.rglob("r.*.*.mca"):
        _, rx, rz, _ = region.name.split(".")
        rx, rz = int(rx), int(rz)
        if rx not in (255, 256) or rz not in (-257, -256):
            continue
        header = region.read_bytes()[:4096]
        for index in range(1024):
            if not int.from_bytes(header[index * 4:index * 4 + 4], "big"):
                continue
            chunk = (rx * 32 + index % 32, rz * 32 + index // 32)
            if chunk not in saved_targets:
                raise SystemExit(f"Mixed cave-mode LOD chunk was saved: {region}: {chunk}")
    print(report.read_text().strip())
    print("PASS: mixed cave-mode LOD targets produced no native, POI or entity saves after shutdown.")
    raise SystemExit(0)
if args.distance_check:
    for region in world.rglob("r.*.*.mca"):
        parts = region.name.split(".")
        if 127 <= int(parts[1]) <= 132 and -130 <= int(parts[2]) <= -126:
            raise SystemExit(f"Distance-check LOD area was saved: {region}")
    print(report.read_text().strip())
    raise SystemExit(0)
if args.dh_plan_check:
    for region in world.rglob("r.*.*.mca"):
        _, x, z, _ = region.name.split(".")
        if abs(int(x)) > 64 or abs(int(z)) > 64:
            raise SystemExit(f"DH plan-check LOD area was saved: {region}")
    print(report.read_text().strip())
    print("PASS: no native, POI or entity region files in any distant test area after shutdown.")
    raise SystemExit(0)
reload_report = run / "integration-reload-result.txt"
reload_report.unlink(missing_ok=True)
reload_command = command[:1] + ["-Dlodgen.test.reload=true"] + [arg for arg in command[1:] if not arg.startswith("-XX:StartFlightRecording=")]
with (run / "integration-reload.log").open("w") as output:
    subprocess.run(reload_command, cwd=run, stdout=output, stderr=subprocess.STDOUT, timeout=60 if args.quick else 300, check=True)
if not reload_report.exists() or not reload_report.read_text().startswith("PASS:"):
    print((run / "integration-reload.log").read_text()[-12000:])
    raise SystemExit(reload_report.read_text() if reload_report.exists() else "Reload server stopped without a report")
if args.task_check:
    import struct
    # 26.x stores even the Overworld under dimensions/minecraft/overworld.
    overworld = world / "dimensions/minecraft/overworld"
    if not overworld.exists():
        overworld = world
    def present(folder, x, z):
        region = overworld / folder / f"r.{x // 32}.{z // 32}.mca"
        if not region.exists():
            return False
        with region.open("rb") as file:
            file.seek(4 * ((x & 31) + (z & 31) * 32))
            return struct.unpack(">I", file.read(4))[0] != 0
    saved = 0
    for x in range(4096 - 24, 4096 + 24):
        for z in range(-4096 - 24, -4096 + 24):
            expected = 4095 <= x < 4097 and -4097 <= z < -4095
            native = present("region", x, z)
            if native != expected:
                raise SystemExit(f"Saved-radius mismatch at {x},{z}: native={native}, expected={expected}")
            if native:
                saved += 1
            if not expected and (present("poi", x, z) or present("entities", x, z)):
                raise SystemExit(f"Outside saved radius has POI/entity data: {x},{z}")
    for folder in ("region", "poi", "entities"):
        for x in range(8192 - 24, 8192 + 24):
            for z in range(-8192 - 24, -8192 + 24):
                if present(folder, x, z):
                    raise SystemExit(f"Automatic custom-center LOD area was saved: {folder} {x},{z}")
    result = {"minecraft": args.mc, "loader": args.loader, "dedicatedAutostartBlocked": "PASS", "automaticTargets": 0, "radiusChunks": 5, "savedRadiusChunks": 1,
              "targetChunks": 100, "nativeChunksSaved": saved, "outsideNativePoiEntityChunks": 0,
              "startPauseResumeStopStatus": "PASS", "automaticResume": "PASS"}
    (run / "task-result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(report.read_text().strip())
    print(reload_report.read_text().strip())
    print(f"PASS: saved exactly {saved} native chunks; LOD-only targets/supporting terrain have no native, POI or entity entries.")
    raise SystemExit(0)
# Audit again after shutdown: buffered writes or unload saves must not escape the
# in-server check. Include every dimension's region, POI, and entity directories.
for region in world.rglob("r.*.*.mca"):
    parts = region.name.split(".")
    x, z = int(parts[1]), int(parts[2])
    extent = args.frontier_radius + args.benchmark + 32
    cx, cz = 10240 + args.benchmark // 2, -10240 + args.benchmark // 2
    if (abs(x - 128) <= 2 and abs(z + 128) <= 2) or (args.benchmark and (((cx - extent) // 32 <= x <= (cx + extent) // 32 and (cz - extent) // 32 <= z <= (cz + extent) // 32) or (abs(x - 375) <= 2 and abs(z + 375) <= 2))):
        raise SystemExit(f"LOD area was saved during shutdown: {region}")
if not any(world.rglob("r.256.-256.mca")):
    raise SystemExit("Normal FULL chunk missing after shutdown")
print(report.read_text().strip())

if args.benchmark:
    print((run / "benchmark-result.json").read_text().strip())
print(reload_report.read_text().strip())
