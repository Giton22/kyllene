#!/usr/bin/env python3
"""Select the public Git revision tested by CI, preserving locked build inputs."""
import argparse
import json
from pathlib import Path


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--ref', required=True)
    parser.add_argument('--commit', required=True)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    source = manifest['modules'][0]['sources'][0]
    if source['type'] != 'git':
        raise ValueError('CI requires a pinned Git source, never a workspace directory')
    source.update(url=args.repository, branch=args.ref, commit=args.commit)
    args.manifest.with_name('ci.json').write_text(json.dumps(manifest, indent=2) + '\n')
