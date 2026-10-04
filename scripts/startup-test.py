#!/usr/bin/env python3
"""Load packaged LODgen with DH/C2ME on client and server, without opening worlds.

Linux clients require an X display (use xvfb-run on CI). Downloads are cached in
build/startup-cache; game directories stay isolated under each target's startup/.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import sys
import time
import zipfile

from minecraft_launcher import CACHE, MATRIX, MOD_VERSION, ROOT, download, mod, prepare_client, run


def test(mc, loader, java, skip_build, modmenu=False):
    target = MATRIX[mc]
    base = ROOT / 'build' / mc / loader / 'startup'
    base.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    print(f'Checking packaged startup: {mc} {loader}', flush=True)
    if not skip_build:
        run([str(ROOT / 'gradlew'), f'-PmcVersion={mc}', f'-Ploader={loader}', '-PstartupTest=true', 'assemble'],
            ROOT, base / 'build.log', 600)
    name = f'lodgen-{mc}-{loader}-{MOD_VERSION}.jar'
    packaged = base / 'libs' / name
    development = ROOT / 'build' / mc / loader / 'libs' / name
    with zipfile.ZipFile(development) as production, zipfile.ZipFile(packaged) as fixture:
        classes = [entry for entry in production.namelist() if entry.endswith('.class')]
        if any(production.read(entry) != fixture.read(entry) for entry in classes):
            raise RuntimeError('Startup fixture differs from installable class files')
        if any('startup/' in entry or 'integration/' in entry for entry in production.namelist()):
            raise RuntimeError('Test code leaked into installable jar')
    # Run the ordinary packaged server loader; the probe stops before initServer opens a world.
    run([sys.executable, str(ROOT / 'scripts/integration-test.py'), '--mc', mc, '--loader', loader,
         '--java', java, '--skip-build', '--startup-only'], ROOT, base / 'server-launch.log', 600)
    print(f'Preparing isolated client: {mc} {loader}', flush=True)
    directory, command = prepare_client(mc, loader, target, java, base)
    mods = directory / 'mods'
    if mods.exists():
        shutil.rmtree(mods)
    mods.mkdir()
    shutil.copyfile(packaged, mods / 'lodgen-startup.jar')
    mod(target['dh'], 'distanthorizons', mods)
    mod(target['c2meFabric' if loader == 'fabric' else 'c2meNeoForge'], 'c2me', mods)
    if loader == 'fabric':
        if modmenu:
            mod(target['modMenu'], 'modmenu', mods)
            command.insert(1, '-Dlodgen.test.modmenu=true')
        api = target['fabricApi']
        cached = download(f'https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/{api}/fabric-api-{api}.jar', CACHE / 'mods' / ('fabric-api-' + api + '.jar'))
        shutil.copyfile(cached, mods / 'fabric-api.jar')
    shutil.rmtree(directory / 'config', ignore_errors=True)
    report = directory / 'startup-result.txt'
    report.unlink(missing_ok=True)
    run(command, directory, base / 'client-launch.log')
    if not report.exists() or not report.read_text().startswith('PASS:'):
        raise RuntimeError('Client exited without a successful startup report')
    if any(directory.rglob('level.dat')) or any(directory.rglob('*.mca')):
        raise RuntimeError('Client startup check opened a world')
    if any((base / 'packaged-server').rglob('*.mca')):
        raise RuntimeError('Server startup check generated chunks')
    result = {'minecraft': mc, 'loader': loader, 'java': target['java'], 'runtimeClassBytesMatch': True,
              'classes': len(classes), 'client': report.read_text().strip(),
              'server': (base / 'packaged-server/startup-result.txt').read_text().strip(),
              'worldOpened': False, 'secondsIncludingSetup': round(time.monotonic() - started, 2),
              'artifactSha256': hashlib.sha256(development.read_bytes()).hexdigest()}
    (base / 'startup-result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(f'PASS: {mc} {loader}: packaged client + server startup, no world ({result["secondsIncludingSetup"]}s)', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mc', choices=MATRIX)
    parser.add_argument('--loader', choices=['fabric', 'neoforge'])
    parser.add_argument('--java', help='Override the runtime Java executable for a single Minecraft version')
    parser.add_argument('--skip-build', action='store_true', help='Reuse an already-built startup fixture')
    parser.add_argument('--modmenu', action='store_true', help='Include Mod Menu and verify its config entry on Fabric')
    args = parser.parse_args()
    if args.java and not args.mc:
        parser.error('--java requires --mc')
    if platform.system() != 'Linux' or not os.environ.get('DISPLAY'):
        parser.error('A Linux X display is required; run with xvfb-run -a')
    for mc in ([args.mc] if args.mc else MATRIX):
        java = args.java
        if not java:
            java_home = os.environ.get(f'JAVA_HOME_{MATRIX[mc]["java"]}_X64')
            local = Path(f'/usr/lib/jvm/java-{MATRIX[mc]["java"]}-openjdk/bin/java')
            java = str(Path(java_home) / 'bin/java') if java_home else str(local) if local.exists() else 'java'
        for loader in ([args.loader] if args.loader else ['fabric', 'neoforge']):
            test(mc, loader, java, args.skip_build, args.modmenu)
