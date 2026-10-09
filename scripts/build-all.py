#!/usr/bin/env python3
"""Build and test every configured target, then collect the installable jars in dist/."""
import hashlib
import json
import shutil
import subprocess

from script_utils import ROOT, load_json, read_mod_version

matrix = load_json("versions.json")
optional_tests = load_json("test-versions.json")
voxy_targets = load_json("voxy-versions.json")
mod_version = read_mod_version()
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
                                   "requiredMods": {"minecraft": mc, **({"fabric-api": "*"} if loader == "fabric" else {})},
                                   "optionalRenderers": {"distanthorizons": matrix[mc]["dhVersion"], "voxy": voxy_targets.get(mc, {}).get(loader)},
                                   "testedVersions": matrix[mc],
                                   "optionalTestMods": {project: versions.get(mc, {}).get(loader) for project, versions in optional_tests.items()}}
(dist / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(f"Built and tested {len(manifest)} jars: {dist}")
