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

ROOT = Path(__file__).resolve().parents[1]
matrix = json.loads((ROOT / "versions.json").read_text())
mod_version = next(line.split("=", 1)[1] for line in (ROOT / "gradle.properties").read_text().splitlines() if line.startswith("modVersion="))
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--mc", choices=matrix, required=True)
parser.add_argument("--loader", choices=("fabric", "neoforge"), required=True)
parser.add_argument("--java", default="java", help="Java 21 for MC 1.21.1; Java 25 for MC 26.x")
parser.add_argument("--skip-build", action="store_true")
parser.add_argument("--quick", action="store_true", help="Skip benchmark warmup and cap server checks at 120/60 seconds")
parser.add_argument("--opencl", action="store_true", help="Install the optional C2ME OpenCL addon and ScalableLux; requires Java 25")
parser.add_argument("--chunky", action="store_true", help="Install Chunky and exercise a real concurrent pregen task")
parser.add_argument("--benchmark", type=int, default=0, help="Generate an N by N chunk benchmark area (N divisible by 4)")
parser.add_argument("--layout", choices=("row", "radial"), default="row", help="Order DH requests by rows or distance from the area's center")
parser.add_argument("--workers", type=int, default=8, help="Concurrent DH benchmark requests")
parser.add_argument("--native-workers", type=int, default=0, help="Override C2ME parallelism in the disposable server (0 keeps its default)")
parser.add_argument("--chunky-working-count", type=int, default=0, help="Override Chunky's in-flight limit in the disposable server")
parser.add_argument("--pipeline-batches", type=int, default=32)
parser.add_argument("--no-spatial-batching", action="store_true", help="Retain DH's original distance-only queue ordering")
parser.add_argument("--dh-executor", action="store_true", help="Use DH's real worldgen executor for the benchmark")
parser.add_argument("--dh-queue", action="store_true", help="Use DH's actual request selection and admission queue")
parser.add_argument("--frontier-radius", type=int, default=0, help="Start DH queue requests on a distant square frontier, in chunks")
parser.add_argument("--store-lods", action="store_true", help="Include DH's actual asynchronous database updates")
parser.add_argument("--worldgen-instance", type=Path, help="Copy WWOO, Continents, their libraries and DH/C2ME settings from a Prism Minecraft directory")
parser.add_argument("--jfr", action="store_true", help="Record the disposable server with Java Flight Recorder")
parser.add_argument("--trace-ownership", action="store_true", help="Log the callers of transient chunk adoption")
parser.add_argument("--vanilla", action="store_true", help="Test without C2ME")
parser.add_argument("--baseline", action="store_true", help="Run the same check with DH's original FEATURES generator")
args = parser.parse_args()
if args.vanilla and args.opencl:
    parser.error("--opencl requires C2ME; omit --vanilla")
if args.benchmark < 0 or args.benchmark % 4:
    parser.error("--benchmark must be zero or a positive multiple of 4")
if not 1 <= args.workers <= 64 or not 1 <= args.pipeline_batches <= 64:
    parser.error("--workers and --pipeline-batches must be between 1 and 64")
if args.frontier_radius < 0 or args.frontier_radius % 4:
    parser.error("--frontier-radius must be a nonnegative multiple of 4")
if args.frontier_radius and not args.dh_queue:
    parser.error("--frontier-radius requires --dh-queue")
if args.native_workers < 0 or args.chunky_working_count < 0:
    parser.error("Worker count overrides must be nonnegative")
target = matrix[args.mc]
base = ROOT / "build" / args.mc / args.loader / "selftest"
run = base / ("packaged-server" + ("-baseline" if args.baseline else "") + ("-vanilla" if args.vanilla else ""))
run.mkdir(parents=True, exist_ok=True)
# Never reuse worlds: previous normal chunks would hide disk-write regressions.
world = run / "world"
if world.exists():
    shutil.rmtree(world)
report = run / "integration-result.txt"
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
    with urllib.request.urlopen(f"https://api.modrinth.com/v2/version/{version}", timeout=60) as response:
        metadata = json.load(response)
    artifact = next(f for f in metadata["files"] if f["primary"])
    download(artifact["url"], run / "mods" / f"{name}.jar", artifact["hashes"]["sha512"])


if not args.skip_build:
    subprocess.run([str(ROOT / "gradlew"), f"-PmcVersion={args.mc}", f"-Ploader={args.loader}",
                    "-PselfTest=true", "build"], cwd=ROOT, check=True)
artifacts = [p for p in (base / "libs").glob("*.jar") if p.name.endswith(f"-{mod_version}.jar")]
if len(artifacts) != 1:
    raise SystemExit("Build the self-test variant first")
(run / "mods").mkdir(exist_ok=True)
shutil.copyfile(artifacts[0], run / "mods" / "lodgen-test.jar")
modrinth(target["dh"], "distanthorizons")
if not args.vanilla:
    modrinth(target["c2meFabric" if args.loader == "fabric" else "c2meNeoForge"], "c2me")
else:
    (run / "mods" / "c2me.jar").unlink(missing_ok=True)
for optional in ("c2me-ocl", "scalablelux", "chunky"):
    (run / "mods" / f"{optional}.jar").unlink(missing_ok=True)
if args.opencl or args.chunky:
    tests = json.loads((ROOT / "test-versions.json").read_text())
    for project in ((["c2me-ocl", "scalablelux"] if args.opencl else []) + (["chunky"] if args.chunky else [])):
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
if args.native_workers:
    c2me_config = run / "config" / "c2me.toml"
    current = c2me_config.read_text() if c2me_config.exists() else "globalExecutorParallelism = \"default\"\n"
    c2me_config.write_text(re.sub(r"(?m)^globalExecutorParallelism\s*=.*$", f"globalExecutorParallelism = {args.native_workers}", current))
(run / "config" / "lodgen.toml").write_text(f"enabled={'false' if args.baseline else 'true'}\npipelineBatches={args.pipeline_batches}\nqueuedBatches=64\nspatialBatching={str(not args.no_spatial_batching).lower()}\n")
(run / "server.properties").write_text("online-mode=false\nserver-port=0\nlevel-seed=123456789\n"
                                       "view-distance=2\nsimulation-distance=2\nmax-tick-time=180000\n")
if args.loader == "fabric":
    version = target["fabricApi"]
    download(f"https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/{version}/fabric-api-{version}.jar",
             run / "mods" / "fabric-api.jar")
    download(f"https://meta.fabricmc.net/v2/versions/loader/{args.mc}/{target['fabricLoader']}/1.1.1/server/jar",
             run / "fabric-server-launch.jar")
    command = [args.java, "-Xmx8G", "-Dlodgen.test.chunky=" + str(args.chunky).lower(), "-Dlodgen.test.benchmark=" + str(args.benchmark), "-jar", "fabric-server-launch.jar", "nogui"]
else:
    nf = target["neoForge"]
    installer = run / f"neoforge-{nf}-installer.jar"
    download(f"https://maven.neoforged.net/releases/net/neoforged/neoforge/{nf}/neoforge-{nf}-installer.jar",
             installer)
    argument_file = run / "libraries" / "net" / "neoforged" / "neoforge" / nf / "unix_args.txt"
    if not argument_file.exists():
        subprocess.run([args.java, "-jar", str(installer), "--installServer"], cwd=run, check=True)
    if argument_file.exists():
        command = [args.java, "-Xmx8G", "-Dlodgen.test.chunky=" + str(args.chunky).lower(), "-Dlodgen.test.benchmark=" + str(args.benchmark), "@" + str(argument_file), "nogui"]
    else:
        launchers = list(run.glob("neoforge-*-server.jar"))
        if len(launchers) != 1:
            raise SystemExit("NeoForge installer did not produce a recognized server launcher")
        command = [args.java, "-Xmx8G", "-Dlodgen.test.chunky=" + str(args.chunky).lower(), "-Dlodgen.test.benchmark=" + str(args.benchmark), "-jar", str(launchers[0]), "nogui"]

benchmark_options = [f"-Dlodgen.test.layout={args.layout}", f"-Dlodgen.test.workers={args.workers}",
                     f"-Dlodgen.test.skipWarmup={str(args.quick).lower()}",
                     f"-Dlodgen.test.nativeWorkers={args.native_workers}",
                     f"-Dlodgen.test.dhExecutor={str(args.dh_executor).lower()}",
                     f"-Dlodgen.test.dhQueue={str(args.dh_queue).lower()}",
                     f"-Dlodgen.test.frontierRadius={args.frontier_radius}",
                     f"-Dlodgen.test.storeLods={str(args.store_lods).lower()}"]
if args.trace_ownership:
    benchmark_options.append("-Dlodgen.test.traceOwnership=true")
if args.chunky_working_count:
    benchmark_options.append(f"-Dchunky.maxWorkingCount={args.chunky_working_count}")
if args.jfr:
    benchmark_options.append("-XX:StartFlightRecording=filename=benchmark.jfr,settings=profile,dumponexit=true")
command[1:1] = benchmark_options
log = run / "integration-server.log"
print(f"Testing packaged {args.mc} {args.loader}; log: {log}", flush=True)
with log.open("w") as output:
    subprocess.run(command, cwd=run, stdout=output, stderr=subprocess.STDOUT, timeout=120 if args.quick else 900, check=True)
if not report.exists() or not report.read_text().startswith("PASS:"):
    print(log.read_text()[-16000:])
    raise SystemExit(report.read_text() if report.exists() else "Server stopped without an integration result")
reload_report = run / "integration-reload-result.txt"
reload_report.unlink(missing_ok=True)
reload_command = command[:1] + ["-Dlodgen.test.reload=true"] + [arg for arg in command[1:] if not arg.startswith("-XX:StartFlightRecording=")]
with (run / "integration-reload.log").open("w") as output:
    subprocess.run(reload_command, cwd=run, stdout=output, stderr=subprocess.STDOUT, timeout=60 if args.quick else 300, check=True)
if not reload_report.exists() or not reload_report.read_text().startswith("PASS:"):
    print((run / "integration-reload.log").read_text()[-12000:])
    raise SystemExit(reload_report.read_text() if reload_report.exists() else "Reload server stopped without a report")
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
