#!/usr/bin/env python3
"""Install Compose's source-built app image and its native launcher."""
import argparse
from pathlib import Path
import shutil


def stage(image, prefix):
    values = {}
    for line in (image / 'lib/app/Kyllene.cfg').read_text().splitlines():
        if '=' in line:
            key, value = line.split('=', 1)
            values.setdefault(key, []).append(value)
    for key in ('app.mainclass', 'app.classpath'):
        if not values.get(key):
            raise ValueError(f'Missing {key} in Compose launcher configuration')
    native = image / 'lib/runtime/lib'
    for name in ('libcef.so', 'libjcef.so', 'jcef_helper', 'icudtl.dat',
                 'resources.pak', 'chrome_100_percent.pak', 'chrome_200_percent.pak',
                 'locales/en-US.pak'):
        if not (native / name).is_file():
            raise ValueError(f'Missing Chromium payload: {name}')
    if not (image / 'bin/Kyllene').is_file():
        raise ValueError('Missing Compose native launcher')

    target = prefix / 'lib/kyllene'
    shutil.copytree(image, target)
    # Upstream restores JCEF's bin payload with copyTo, which loses executable mode.
    (target / 'lib/runtime/lib/jcef_helper').chmod(0o755)
    launcher = prefix / 'bin/kyllene'
    launcher.parent.mkdir(parents=True, exist_ok=True)
    launcher.symlink_to('../lib/kyllene/bin/Kyllene')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('image', type=Path)
    parser.add_argument('prefix', type=Path)
    options = parser.parse_args()
    stage(options.image, options.prefix)
