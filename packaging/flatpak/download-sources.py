#!/usr/bin/env python3
"""Download a manifest's sources into flatpak-builder's download cache, retrying when a server turns us away.

flatpak-builder fetches about 1,400 Maven files one after another and stops at the first error, so a single HTTP
429 from Maven Central fails the whole build. Each attempt here keeps what earlier ones downloaded. The build then
runs with --disable-download.

Before downloading it removes cached downloads the manifest no longer lists, so a CI cache restored from an older
manifest doesn't grow with every dependency update, and any whose contents don't match the checksum they're filed
under. flatpak-builder checks a file's checksum only when it downloads it, not when it finds it already in
downloads/, so a damaged or tampered CI cache would otherwise go into the build unchecked.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time

CHECKSUMS = ('md5', 'sha1', 'sha256', 'sha512')
# flatpak-builder files a download under downloads/<checksum>/<name>; the checksum's length says which kind it is.
ALGORITHMS = {32: 'md5', 40: 'sha1', 64: 'sha256', 128: 'sha512'}


def sources(manifest):
    """Every source in [manifest], with the source files it names (such as gradle-sources.json) read in."""
    found = []

    def walk(node, folder):
        if isinstance(node, dict):
            for key, value in node.items():
                if key == 'sources' and isinstance(value, list):
                    for source in value:
                        if isinstance(source, str):
                            path = folder / source
                            walk({'sources': json.loads(path.read_text())}, path.parent)
                        else:
                            found.append(source)
                            walk(source, folder)
                else:
                    walk(value, folder)
        elif isinstance(node, list):
            for item in node:
                walk(item, folder)

    walk(json.loads(manifest.read_text()), manifest.parent)
    return found


def checksums(manifest):
    """Every checksum the manifest's sources give: flatpak-builder files a download under one of them."""
    return {s[key] for s in sources(manifest) if isinstance(s, dict) for key in CHECKSUMS if key in s}


def intact(entry):
    """Whether [entry] is a folder holding only files whose contents match the checksum it is named after."""
    algorithm = ALGORITHMS.get(len(entry.name))
    if algorithm is None or entry.is_symlink() or not entry.is_dir():
        return False
    files = list(entry.iterdir())
    for file in files:
        if file.is_symlink() or not file.is_file():
            return False
        digest = hashlib.new(algorithm)
        with file.open('rb') as stream:
            for block in iter(lambda: stream.read(1 << 20), b''):
                digest.update(block)
        if digest.hexdigest() != entry.name.lower():
            return False
    return bool(files)


def prune(downloads, keep):
    """Removes the entries in [downloads] that aren't named after a checksum in [keep], or whose files don't match
    it; returns how many."""
    removed = 0
    if downloads.is_dir():
        for entry in downloads.iterdir():
            if entry.name not in keep or not intact(entry):
                shutil.rmtree(entry) if entry.is_dir() and not entry.is_symlink() else entry.unlink()
                removed += 1
    return removed


def download(command, attempts, delay, sleep=time.sleep):
    """Runs [command] until it succeeds, at most [attempts] times, waiting [delay] seconds and then twice as long."""
    for attempt in range(1, attempts + 1):
        if subprocess.run(command).returncode == 0:
            return True
        if attempt < attempts:
            print(f'::warning::Downloading sources failed (attempt {attempt} of {attempts}); retrying in {delay}s',
                  flush=True)
            sleep(delay)
            delay *= 2
    return False


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('build_dir')
    parser.add_argument('manifest', type=Path)
    parser.add_argument('--state-dir', type=Path, default=Path('.flatpak-builder'))
    parser.add_argument('--attempts', type=int, default=5)
    parser.add_argument('--delay', type=int, default=30, help='seconds before the first retry')
    args = parser.parse_args()
    removed = prune(args.state_dir / 'downloads', checksums(args.manifest))
    print(f'Removed {removed} cached downloads the manifest no longer lists or that are damaged', flush=True)
    command = ['flatpak-builder', '--user', '--download-only', '--state-dir=' + str(args.state_dir),
               args.build_dir, str(args.manifest)]
    sys.exit(0 if download(command, args.attempts, args.delay) else 1)
