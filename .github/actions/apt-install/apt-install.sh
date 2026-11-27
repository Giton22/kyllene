#!/usr/bin/env bash
# Installs packages from Ubuntu's own sources (ubuntu.sources). Every other source the runner image adds is moved
# aside first, so it can't fail `apt-get update`; an error from the Ubuntu mirrors still fails it.
set -euo pipefail
sudo mkdir -p /etc/apt/sources.list.d.disabled
for source in /etc/apt/sources.list.d/*; do
  [ -e "$source" ] && [ "${source##*/}" != ubuntu.sources ] || continue
  echo "Disabling $source"
  sudo mv "$source" /etc/apt/sources.list.d.disabled/
done
sudo apt-get update
sudo apt-get install -y "$@"
