#!/usr/bin/env python3
"""Build and test every configured target, then collect the installable jars in dist/."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
matrix = json.loads((ROOT / "versions.json").read_text())
optional_tests = json.loads((ROOT / "test-versions.json").read_text())
voxy_targets = json.loads((ROOT / "voxy-versions.json").read_text())
mod_version = next(line.split("=", 1)[1] for line in (ROOT / "gradle.properties").read_text().splitlines() if line.startswith("modVersion="))
logs = ROOT / "build" / "matrix-logs"
logs.mkdir(parents=True, exist_ok=True)
dist = ROOT / "dist"
dist.mkdir(exist_ok=True)
manifest = {}
for mc in matrix:
    for loader in ("fabric", "neoforge"):
        name = f"{mc}-{loader}"
        print(f"Building {name}...", flush=True)
        log = logs / f"{name}.log"
        with log.open("w") as output:
            result = subprocess.run([str(ROOT / "gradlew"), f"-PmcVersion={mc}", f"-Ploader={loader}", "build"],
                                    cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
        if result.returncode:
            print(log.read_text()[-8000:])
            raise SystemExit(f"Build failed: {name}; full log: {log}")
        jars = [p for p in (ROOT / "build" / mc / loader / "libs").glob("*.jar") if p.name.endswith(f"-{mod_version}.jar")]
        if len(jars) != 1:
            raise SystemExit(f"Expected one installable jar for {name}, got {jars}")
        artifact = dist / jars[0].name
        shutil.copyfile(jars[0], artifact)
        manifest[artifact.name] = {"sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
                                   "minecraft": mc, "loader": loader,
                                   "requiredMods": {"minecraft": mc},
                                   "optionalRenderers": {"distanthorizons": matrix[mc]["dhVersion"], "voxy": voxy_targets.get(mc, {}).get(loader)},
                                   "testedVersions": matrix[mc],
                                   "optionalTestMods": {project: versions.get(mc, {}).get(loader) for project, versions in optional_tests.items()}}
(dist / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(f"Built and tested {len(manifest)} jars: {dist}")
