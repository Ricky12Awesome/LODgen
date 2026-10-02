#!/usr/bin/env python3
"""Packaged Voxy clients without DH. --world checks <=88 target chunks per target.

Requires an X display. Uses disposable directories; never changes Prism instances.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('startup_runner', ROOT / 'scripts/startup-test.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
TARGETS = {(mc, loader): mods for mc, loaders in json.loads((ROOT / 'voxy-versions.json').read_text()).items()
           for loader, mods in loaders.items()}


def test(mc, loader, world, java_override, reload, performance=None):
    benchmark = performance.benchmark if performance else 0
    started = time.monotonic()
    target = runner.MATRIX[mc]
    java = java_override or f'/usr/lib/jvm/java-{target["java"]}-openjdk/bin/java'
    if not Path(java).exists():
        java = 'java'
    base = ROOT / 'build' / mc / loader / ('voxy-performance' if benchmark else 'voxy-test')
    base.mkdir(parents=True, exist_ok=True)
    runner.run([str(ROOT / 'gradlew'), f'-PmcVersion={mc}', f'-Ploader={loader}', '-PstartupTest=true', 'assemble'],
               ROOT, base / 'build.log', 600)
    name = f'lodgen-{mc}-{loader}-{runner.MOD_VERSION}.jar'
    fixture = ROOT / 'build' / mc / loader / 'startup/libs' / name
    production = ROOT / 'build' / mc / loader / 'libs' / name
    with zipfile.ZipFile(production) as installed, zipfile.ZipFile(fixture) as probe:
        assert all(installed.read(n) == probe.read(n) for n in installed.namelist() if n.endswith('.class'))
    directory, command = runner.prepare_client(mc, loader, target, java, base)
    for folder in ['mods', 'config', 'saves']:
        shutil.rmtree(directory / folder, ignore_errors=True)
    mods = directory / 'mods'
    mods.mkdir()
    shutil.copyfile(fixture, mods / 'lodgen-test.jar')
    for project, version in TARGETS[mc, loader].items():
        runner.mod(version, project, mods)
        # Roxy participates in both service and game module layers. Preserve its
        # published filename so the service module doesn't become named "roxy",
        # colliding with the metadata mod that its locator creates.
        if project == 'roxy':
            metadata = json.loads((runner.CACHE / 'mods' / (version + '.json')).read_text())
            filename = next(f['filename'] for f in metadata['files'] if f['primary'])
            (mods / 'roxy.jar').rename(mods / filename)
    runner.mod(target['c2meFabric' if loader == 'fabric' else 'c2meNeoForge'], 'c2me', mods)
    if loader == 'fabric':
        api = target['fabricApi']
        cached = runner.download(f'https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/{api}/fabric-api-{api}.jar',
                                 runner.CACHE / 'mods' / ('fabric-api-' + api + '.jar'))
        shutil.copyfile(cached, mods / 'fabric-api.jar')
    if benchmark:
        optimization = json.loads((ROOT / 'test-versions.json').read_text())
        for project in ['c2me-ocl', 'scalablelux', 'chunky', 'lithium', 'ferritecore', 'structure-layout-optimizer', 'resourceful-config', 'zfastnoise']:
            runner.mod(optimization[project][mc][loader], project, mods)
    config = directory / 'config'
    config.mkdir()
    (config / 'lodgen.toml').write_text('enabled=false\ncpuLoad=1\ngenerationDistance=1\n')
    # Exercise actual ingestion/storage while avoiding an expensive LOD render.
    (config / 'voxy-config.json').write_text(json.dumps({'enabled': True, 'enable_rendering': False,
        'ingest_enabled': True, 'section_render_distance': 1, 'service_threads': 2}))
    (directory / 'options.txt').write_text('renderDistance:2\nsimulationDistance:2\npauseOnLostFocus:false\nguiScale:2\nmaxFps:30\nenableVsync:false\n')
    if benchmark:
        (config / 'c2me.toml').write_text(f'version=3\nglobalExecutorParallelism={performance.native_workers}\n')
        command = [arg for arg in command if not arg.startswith('-Xmx')]
        command[1:1] = ['-Xmx' + performance.heap, '-XX:+UseZGC', '-XX:+UseCompactObjectHeaders',
            f'-Dlodgen.test.voxyBenchmark={benchmark}', f'-Dlodgen.test.cpuLoad={performance.cpu_load}',
            f'-Dlodgen.test.nativeWorkers={performance.native_workers}', '-Dchunky.maxWorkingCount=768']
        if performance.chunky_native_only:
            command.insert(1, '-Dlodgen.test.chunkyNativeOnly=true')
        if performance.jfr:
            command.insert(1, '-XX:StartFlightRecording=filename=benchmark.jfr,settings=profile,dumponexit=true')
    report = directory / 'startup-result.txt'
    report.unlink(missing_ok=True)
    command[1:1] = ['-Dlodgen.test.voxy=true', f'-Dlodgen.test.voxyWorld={str(world).lower()}']
    print(f'Testing Voxy without DH: {mc} {loader}, small world={world}', flush=True)
    runner.run(command, directory, base / 'client-launch.log', 600 if benchmark else 180)
    result = report.read_text().strip() if report.exists() else 'FAIL: no report'
    if not result.startswith('PASS:'):
        raise RuntimeError(result)
    if benchmark:
        # Verify no native region/POI/entity files in either LOD benchmark square.
        for region in (directory / 'saves').rglob('r.*.*.mca'):
            _, rx, rz, _ = region.name.split('.')
            if (319 <= int(rx) <= 325 and -321 <= int(rz) <= -315) or (374 <= int(rx) <= 379 and -376 <= int(rz) <= -371):
                raise RuntimeError(f'LOD-only benchmark saved native terrain: {region}')
        data = json.loads((directory / 'benchmark-result.json').read_text())
        (base / 'result.json').write_text(json.dumps(data, indent=2) + '\n')
        print(result, flush=True)
        print(json.dumps(data), flush=True)
        return
    reload_result = None
    if reload:
        report.unlink()
        command.insert(1, '-Dlodgen.test.voxyReload=true')
        runner.run(command, directory, base / 'client-reload.log', 180)
        reload_result = report.read_text().strip() if report.exists() else 'FAIL: no reload report'
        if not reload_result.startswith('PASS:'):
            raise RuntimeError(reload_result)
    if world:
        for region in (directory / 'saves').rglob('r.*.*.mca'):
            parts = region.name.split('.')
            if 126 <= int(parts[1]) <= 131 and -131 <= int(parts[2]) <= -126:
                raise RuntimeError(f'Voxy-only native area saved: {region}')
        # Only the 2x2 inner saved square at custom center 1024,-1024 may persist.
        import struct
        world_root = directory / 'saves' / 'lodgen-voxy-check'
        if mc != '1.21.1':
            world_root = world_root / 'dimensions' / 'minecraft' / 'overworld'
        def present(folder, x, z):
            region = world_root / folder / f'r.{x // 32}.{z // 32}.mca'
            if not region.exists(): return False
            with region.open('rb') as stream:
                stream.seek(4 * ((x & 31) + (z & 31) * 32))
                return struct.unpack('>I', stream.read(4))[0] != 0
        for x in range(1000, 1048):
            for z in range(-1048, -1000):
                expected = 1023 <= x < 1025 and -1025 <= z < -1023
                if present('region', x, z) != expected:
                    raise RuntimeError(f'Voxy automatic saved-radius mismatch at {x},{z}')
                if not expected and (present('poi', x, z) or present('entities', x, z)):
                    raise RuntimeError(f'Voxy supporting chunks were saved at {x},{z}')
        coverage = list((directory / 'saves').rglob('*.tiles'))
        if not coverage:
            raise RuntimeError('Voxy shutdown did not checkpoint completed generation')
    elif any((directory / 'saves').rglob('level.dat')):
        raise RuntimeError('Startup check opened a world')
    record = {'minecraft': mc, 'loader': loader, 'mods': TARGETS[mc, loader], 'distantHorizonsInstalled': False,
              'worldTested': world, 'result': result, 'runtimeClassBytesMatch': True,
              'noFarNativeRegionFiles': world, 'customCenter': world, 'nativeChunksSaved': 4 if world else 0,
              'crossDimensionCommands': world, 'targetChunkLimit': 88 if world else 0,
              'reloadResult': reload_result, 'seconds': round(time.monotonic() - started, 2)}
    (base / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
    print(result, flush=True)
    if reload_result:
        print(reload_result, flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mc', choices=['1.21.1', '26.1.2', '26.2'])
    parser.add_argument('--world', action='store_true')
    parser.add_argument('--reload', action='store_true', help='Also reopen the small test world and check persisted LODs and coverage')
    parser.add_argument('--java')
    parser.add_argument('--benchmark', type=int, default=0, help='26.2 vanilla benchmark radius in chunks (64 = 16384 targets)')
    parser.add_argument('--heap', default='32G')
    parser.add_argument('--cpu-load', type=int, choices=range(1, 6), default=5)
    parser.add_argument('--native-workers', type=int, default=32)
    parser.add_argument('--jfr', action='store_true')
    parser.add_argument('--chunky-native-only', action='store_true', help='Disable Voxy ingestion only during the Chunky baseline')
    args = parser.parse_args()
    if not os.environ.get('DISPLAY'):
        parser.error('An X display is required; run with xvfb-run -a')
    if args.benchmark and (args.mc != '26.2' or args.world or args.reload):
        parser.error('--benchmark requires --mc 26.2 and cannot be combined with the small world/reload checks')
    if args.benchmark < 0 or args.benchmark > 64:
        parser.error('Benchmark radius must be between 1 and 64 chunks')
    if args.reload and not args.world:
        parser.error('--reload requires --world')
    for mc, loader in TARGETS:
        if not args.mc or mc == args.mc:
            test(mc, loader, args.world, args.java, args.reload, args)
