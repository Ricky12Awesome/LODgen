"""Minecraft client setup and launch helpers shared by startup and Voxy checks."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import ssl
import subprocess
import time
import urllib.request
import urllib.error
import zipfile

from script_utils import ROOT, load_json, read_mod_version

MATRIX = load_json('versions.json')
MOD_VERSION = read_mod_version()
CACHE = ROOT / 'build/startup-cache'
TLS_CONTEXT = ssl.create_default_context()


def fetch_json(url, destination):
    download(url, destination)
    return json.loads(destination.read_text())


def download(url, destination, sha1=None, sha512=None):
    destination = Path(destination)
    expected, algorithm = (sha512, 'sha512') if sha512 else (sha1, 'sha1')
    if destination.exists() and (not expected or hashlib.new(algorithm, destination.read_bytes()).hexdigest() == expected):
        return destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + '.tmp')
    try:
        for attempt in range(3):
            try:
                with urllib.request.urlopen(url, timeout=60, context=TLS_CONTEXT) as response, temporary.open('wb') as output:
                    shutil.copyfileobj(response, output)
                break
            except (urllib.error.URLError, TimeoutError) as failure:
                if isinstance(failure, urllib.error.HTTPError) and failure.code < 500 and failure.code != 429:
                    raise
                if attempt == 2:
                    raise RuntimeError(f'Cannot download {url}') from failure
                time.sleep(attempt + 1)
        if expected and hashlib.new(algorithm, temporary.read_bytes()).hexdigest() != expected:
            raise RuntimeError(f'Checksum mismatch: {url}')
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    return destination


def run(command, directory, log, timeout=240):
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open('w') as output:
        try:
            subprocess.run(command, cwd=directory, stdout=output, stderr=subprocess.STDOUT, timeout=timeout, check=True)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            print(log.read_text(errors='replace')[-14000:])
            raise


def allowed(item):
    if 'rules' not in item:
        return True
    result = False
    for rule in item['rules']:
        system = rule.get('os', {})
        matches = system.get('name', 'linux') == 'linux'
        matches &= not system.get('arch') or re.fullmatch(system['arch'], platform.machine()) is not None
        matches &= not system.get('version') or re.search(system['version'], platform.release()) is not None
        matches &= not any(rule.get('features', {}).values())
        if matches:
            result = rule['action'] == 'allow'
    return result


def library_path(coordinate):
    group, name, version, *classifier = coordinate.split(':')
    suffix = '-' + classifier[0] if classifier else ''
    return f"{group.replace('.', '/')}/{name}/{version}/{name}-{version}{suffix}.jar"


def libraries(metadata, directory):
    paths, native_archives, jobs = {}, [], []
    for library in metadata.get('libraries', []):
        if not allowed(library):
            continue
        artifact = library.get('downloads', {}).get('artifact')
        if artifact:
            relative = artifact['path']
            path = directory / relative
            # Empty URLs identify local files produced by the NeoForge installer.
            if artifact.get('url'):
                jobs.append((artifact['url'], path, artifact.get('sha1')))
        else:
            relative = library_path(library['name'])
            path = directory / relative
            jobs.append((library.get('url', 'https://libraries.minecraft.net/') + relative, path, None))
        coordinate = library['name'].split(':')
        paths[':'.join(coordinate[:2] + coordinate[3:])] = path
        if 'natives-linux' in relative:
            native_archives.append(path)
        native = library.get('natives', {}).get('linux')
        if native:
            native = native.replace('${arch}', '64')
            artifact = library['downloads']['classifiers'][native]
            path = directory / artifact['path']
            jobs.append((artifact['url'], path, artifact.get('sha1')))
            native_archives.append(path)
    with ThreadPoolExecutor(max_workers=12) as workers:
        list(workers.map(lambda job: download(*job), jobs))
    return paths, native_archives


def assets(metadata):
    root = CACHE / 'assets'
    index = metadata['assetIndex']
    contents = fetch_json(index['url'], root / 'indexes' / (index['id'] + '.json'))
    unimined = Path.home() / '.gradle/caches/unimined/assets/objects'
    def object_file(item):
        digest = item['hash']
        relative = digest[:2] + '/' + digest
        destination = root / 'objects' / relative
        cached = unimined / relative
        if not destination.exists() and cached.exists() and hashlib.sha1(cached.read_bytes()).hexdigest() == digest:
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(cached, destination)
        download('https://resources.download.minecraft.net/' + relative, destination, digest)
    # Different logical resource names can reference the same object.
    objects = {item['hash']: item for item in contents['objects'].values()}
    with ThreadPoolExecutor(max_workers=12) as workers:
        list(workers.map(object_file, objects.values()))
    return root


def mod(version, name, directory):
    metadata = fetch_json(f'https://api.modrinth.com/v2/version/{version}', CACHE / 'mods' / (version + '.json'))
    artifact = next(file for file in metadata['files'] if file['primary'])
    cached = download(artifact['url'], CACHE / 'mods' / (version + '.jar'), sha512=artifact['hashes']['sha512'])
    shutil.copyfile(cached, directory / (name + '.jar'))


def expand(arguments, replacements):
    result = []
    for item in arguments:
        if isinstance(item, dict):
            if not allowed(item):
                continue
            values = item['value'] if isinstance(item['value'], list) else [item['value']]
        else:
            values = [item]
        for value in values:
            value = re.sub(r'\$\{([^}]+)\}', lambda match: replacements[match[1]], value)
            result.append(value)
    return result


def prepare_client(mc, loader, target, java, base):
    directory = base / 'client'
    directory.mkdir(parents=True, exist_ok=True)
    installation = base / 'client-install'
    installation.mkdir(parents=True, exist_ok=True)
    manifest = fetch_json('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json', CACHE / 'version-manifest.json')
    version = next(item for item in manifest['versions'] if item['id'] == mc)
    vanilla = fetch_json(version['url'], CACHE / 'versions' / (mc + '.json'))
    client = vanilla['downloads']['client']
    client_jar = download(client['url'], CACHE / 'versions' / (mc + '.jar'), client['sha1'])
    asset_root = assets(vanilla)
    library_dir = installation / 'libraries'
    paths, natives = libraries(vanilla, library_dir)
    if loader == 'fabric':
        profile = fetch_json(f"https://meta.fabricmc.net/v2/versions/loader/{mc}/{target['fabricLoader']}/profile/json",
                             CACHE / 'versions' / f"fabric-{mc}-{target['fabricLoader']}.json")
    else:
        nf = target['neoForge']
        installer = download(f'https://maven.neoforged.net/releases/net/neoforged/neoforge/{nf}/neoforge-{nf}-installer.jar',
                             CACHE / 'installers' / f'neoforge-{nf}.jar')
        profile_file = installation / 'versions' / ('neoforge-' + nf) / ('neoforge-' + nf + '.json')
        if not profile_file.exists():
            vanilla_dir = installation / 'versions' / mc
            vanilla_dir.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(client_jar, vanilla_dir / (mc + '.jar'))
            (vanilla_dir / (mc + '.json')).write_text(json.dumps(vanilla))
            (installation / 'launcher_profiles.json').write_text('{"profiles":{}}\n')
            run([java, '-jar', str(installer), '--installClient', str(installation)], installation, base / 'client-install.log', 600)
        profile = json.loads(profile_file.read_text())
    loader_paths, loader_natives = libraries(profile, library_dir)
    paths.update(loader_paths)
    natives += loader_natives
    native_dir = installation / 'natives'
    native_dir.mkdir(exist_ok=True)
    for archive in natives:
        with zipfile.ZipFile(archive) as source:
            for entry in source.namelist():
                if entry.endswith('.so'):
                    (native_dir / Path(entry).name).write_bytes(source.read(entry))
    classpath = os.pathsep.join(str(path) for path in [*paths.values(), client_jar])
    replacements = {
        'natives_directory': str(native_dir), 'launcher_name': 'LODgen-startup-test', 'launcher_version': '0.0.0',
        'classpath': classpath, 'classpath_separator': os.pathsep, 'library_directory': str(library_dir),
        'auth_player_name': 'LODgenTest', 'version_name': profile['id'], 'game_directory': str(directory),
        'assets_root': str(asset_root), 'assets_index_name': vanilla['assetIndex']['id'],
        'auth_uuid': '00000000000000000000000000000001', 'auth_access_token': '0', 'user_type': 'legacy',
        'version_type': 'release', 'clientid': '', 'auth_xuid': '', 'user_properties': '{}'
    }
    jvm = expand(vanilla['arguments']['jvm'] + profile.get('arguments', {}).get('jvm', []), replacements)
    # ModLauncher must ignore the original client jar while loading NeoForge's patched classes.
    jvm = [arg + ',' + client_jar.name if arg.startswith('-DignoreList=') else arg for arg in jvm]
    game = expand(vanilla['arguments']['game'] + profile.get('arguments', {}).get('game', []), replacements)
    return directory, [java, '-Xmx2G', *jvm, profile['mainClass'], *game, '--width', '960', '--height', '540']


