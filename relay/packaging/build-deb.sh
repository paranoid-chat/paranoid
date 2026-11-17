#!/bin/sh
# build-deb.sh ARCH RELAYD SORTIE : construit paranoid-relay_VERSION_ARCH.deb
# ARCH au format Debian (amd64, arm64), RELAYD = binaire statique compilé.
set -eu
arch=$1 relayd=$2 out=$3
here=$(cd "$(dirname "$0")" && pwd)
version=$(cat "$here/VERSION")
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

cp -a "$here/root/." "$tmp/"
install -D -m 0755 "$relayd" "$tmp/usr/bin/relayd"
chmod 0755 "$tmp/usr/sbin/paranoid-relay"
find "$tmp/usr/lib" "$tmp/usr/share" -type f -exec chmod 0644 {} +
mkdir -p "$tmp/DEBIAN"
sed -e "s/@VERSION@/$version/" -e "s/@ARCH@/$arch/" "$here/debian/control.in" >"$tmp/DEBIAN/control"
for f in postinst prerm postrm; do
	install -m 0755 "$here/debian/$f" "$tmp/DEBIAN/$f"
done
mkdir -p "$out"
dpkg-deb --root-owner-group -Zxz --build "$tmp" "$out/paranoid-relay_${version}_${arch}.deb"
