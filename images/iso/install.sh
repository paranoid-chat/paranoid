#!/bin/sh
# install.sh DOSSIER : dans le système installé, installe le relais depuis le dépôt local DOSSIER
# (paquets de l'ISO, sans réseau). Appelé par late.sh, et par build.sh pour le test.
set -eu
d=$1
chmod 0755 "$d"
echo "deb [trusted=yes] file:$d ./" >"$d/local.list"
o="-o Dir::Etc::sourcelist=$d/local.list -o Dir::Etc::sourceparts=- -o APT::Get::List-Cleanup=0"
# shellcheck disable=SC2086
apt-get $o update
# shellcheck disable=SC2046,SC2086
DEBIAN_FRONTEND=noninteractive apt-get $o install -y --no-install-recommends \
	-o Dpkg::Options::=--force-confold $(cat "$d/packages.list") paranoid-relay
