#!/bin/bash
set -euo pipefail
version=$1 deb=$2 out=$3
latest=https://downloads.raspberrypi.com/raspios_lite_arm64_latest
work=$(mktemp -d)
mnt=$work/root
loop=""

cleanup() {
	set +e
	for m in dev/pts dev proc sys boot/firmware ""; do
		mountpoint -q "$mnt/$m" && umount -l "$mnt/$m"
	done
	[ -n "$loop" ] && losetup -d "$loop"
	rm -rf "$work"
}
trap cleanup EXIT

url=$(curl -fsSLI -o /dev/null -w '%{url_effective}' "$latest")
echo "Raspberry Pi OS Lite : $url"
curl -fsSL "$url" -o "$work/raspios.img.xz"
expected=$(curl -fsSL "$url.sha256" | awk '{ print $1 }')
echo "$expected  $work/raspios.img.xz" | sha256sum -c -
xz -d "$work/raspios.img.xz"
img=$work/raspios.img

loop=$(losetup -P --show -f "$img")
mkdir -p "$mnt"
mount "${loop}p2" "$mnt"
mount "${loop}p1" "$mnt/boot/firmware"
mount --bind /dev "$mnt/dev"
mount --bind /dev/pts "$mnt/dev/pts"
mount -t proc proc "$mnt/proc"
mount -t sysfs sys "$mnt/sys"

echo "----- diagnostic de l'image d'origine -----"
cat "$mnt/boot/firmware/cmdline.txt"
ls "$mnt/boot/firmware" | head -50
for f in user-data meta-data network-config; do
	[ -f "$mnt/boot/firmware/$f" ] && { echo "--- $f"; cat "$mnt/boot/firmware/$f"; }
done
chroot "$mnt" systemctl list-unit-files --no-pager | grep -iE 'userconf|cloud|firstboot|resize|ssh|getty@' || true
echo "-------------------------------------------"

resolv_bak=""
if [ -e "$mnt/etc/resolv.conf" ] || [ -L "$mnt/etc/resolv.conf" ]; then
	resolv_bak=$work/resolv.conf
	mv "$mnt/etc/resolv.conf" "$resolv_bak"
fi
cp /etc/resolv.conf "$mnt/etc/resolv.conf"

cp "$deb" "$mnt/tmp/paranoid-relay.deb"
chroot "$mnt" /bin/bash -euo pipefail <<'EOF'
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q /tmp/paranoid-relay.deb unattended-upgrades sudo console-setup
rm -f /tmp/paranoid-relay.deb
apt-get clean

rm -f /etc/paranoid-relay/relay.crt /etc/paranoid-relay/relay.key

cat >/etc/apt/apt.conf.d/20auto-upgrades <<'APT'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT
printf 'net.ipv6.conf.all.disable_ipv6 = 1\nnet.ipv6.conf.default.disable_ipv6 = 1\n' >/etc/sysctl.d/90-paranoid-relay.conf

systemctl mask userconfig.service >/dev/null 2>&1 || true
mkdir -p /etc/cloud/cloud.cfg.d
cat >/etc/cloud/cloud.cfg.d/99-paranoid-relay.cfg <<'CLOUD'
network: {config: disabled}
preserve_hostname: true
users: []
ssh_pwauth: false
CLOUD
systemctl disable ssh.service >/dev/null 2>&1 || true

mkdir -p /etc/paranoid-relay
touch /etc/paranoid-relay/firstboot
systemctl enable paranoid-relay-firstboot.service
EOF

cat >"$mnt/boot/firmware/user-data" <<'EOF'
#cloud-config
users: []
preserve_hostname: true
ssh_pwauth: false
EOF

rm -f "$mnt/etc/resolv.conf"
[ -n "$resolv_bak" ] && mv "$resolv_bak" "$mnt/etc/resolv.conf"

echo "----- vérification -----"
chroot "$mnt" dpkg -l paranoid-relay unattended-upgrades | tail -2
chroot "$mnt" systemctl is-enabled paranoid-relay-firstboot.service
ls -l "$mnt/etc/paranoid-relay"

sync
for m in dev/pts dev proc sys boot/firmware ""; do umount "$mnt/$m"; done
losetup -d "$loop"
loop=""

mkdir -p "$out"
name="paranoid-relay-$version-raspberrypi-arm64.img.xz"
xz -T0 -6 -c "$img" >"$out/$name"
ls -l "$out/$name"
