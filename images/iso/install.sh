#!/bin/sh
set -eu
d=$1
chmod 0755 "$d"
echo "deb [trusted=yes] file:$d ./" >"$d/local.list"
o="-o Dir::Etc::sourcelist=$d/local.list -o Dir::Etc::sourceparts=- -o APT::Get::List-Cleanup=0"
apt-get $o update
DEBIAN_FRONTEND=noninteractive apt-get $o install -y --no-install-recommends \
	-o Dpkg::Options::=--force-confold $(cat "$d/packages.list") paranoid-relay
