#!/bin/sh
# images/iso/build.sh VERSION DEB SORTIE
# Construit paranoid-relay-VERSION-amd64.iso dans SORTIE. À lancer dans un conteneur debian:trixie (root).
#   1. ISO netinst officielle de Debian 13 (somme SHA-256 vérifiée) : système de base hors ligne ;
#   2. /paranoid/debs : paranoid-relay et les paquets de packages.list avec leurs dépendances,
#      pris dans la même version de Debian (sans les mises à jour de sécurité, pour coller à l'ISO) ;
#   3. /paranoid/preseed.cfg, disk.sh (choix du disque + confirmation tapée), postinst.sh ;
#   4. menus de démarrage BIOS et UEFI remplacés : deux entrées, aucune par défaut, attente sans délai.
set -eu
version=$1 deb=$2 out=$3
here=$(cd "$(dirname "$0")" && pwd)
work=/tmp/paranoid-iso
rm -rf "$work"
mkdir -p "$work/debs" "$out"

export DEBIAN_FRONTEND=noninteractive
# Seulement trixie (version ponctuelle courante), comme l'ISO netinst « current ».
rm -f /etc/apt/sources.list.d/*.sources /etc/apt/sources.list
cat >/etc/apt/sources.list.d/debian.sources <<'EOF'
Types: deb
URIs: http://deb.debian.org/debian
Suites: trixie
Components: main
Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg
EOF
apt-get update -q
apt-get install -y -q --no-install-recommends xorriso curl ca-certificates >/dev/null

base=https://cdimage.debian.org/debian-cd/current/amd64/iso-cd
curl -fsSL "$base/SHA256SUMS" -o "$work/SHA256SUMS"
netinst=$(grep -oE 'debian-[0-9.]+-amd64-netinst\.iso' "$work/SHA256SUMS" | head -1)
echo "ISO de base : $netinst"
curl -fsSL "$base/$netinst" -o "$work/$netinst"
(cd "$work" && grep " $netinst\$" SHA256SUMS | sha256sum -c -)

# Paquets du relais et dépendances absentes du conteneur (sur-ensemble de ce qui manque au système de base).
# shellcheck disable=SC2046
apt-get install -y -q --download-only --no-install-recommends -o Dir::Cache::archives="$work/debs" \
	$(cat "$here/packages.list") "$deb" >/dev/null
cp "$deb" "$work/debs/"
rm -rf "$work/debs/partial" "$work/debs/lock"
echo "Paquets ajoutés : $(find "$work/debs" -name '*.deb' | wc -l) ($(du -sh "$work/debs" | cut -f1))"

iso=$work/$netinst
xorriso -osirrox on -indev "$iso" -extract /boot/grub/grub.cfg "$work/grub.orig" >/dev/null 2>&1
echo "----- grub.cfg d'origine (extrait) -----"
grep -nE 'menuentry|linux|initrd' "$work/grub.orig" | head -20
echo "----------------------------------------"
xorriso -indev "$iso" -find /install.amd -maxdepth 1 -exec lsdl 2>/dev/null | grep -E 'vmlinuz|initrd' ||
	{ echo "noyau de l'installeur introuvable" >&2; exit 1; }

common="auto=true priority=critical preseed/file=/cdrom/paranoid/preseed.cfg"
fr="$common locale=fr_FR.UTF-8 language=fr country=FR keymap=fr"
en="$common locale=en_US.UTF-8 language=en country=US keymap=us"

cat >"$work/grub.cfg" <<EOF
# Relais Paranoid : aucune entrée par défaut, attente sans délai.
set timeout=-1
if loadfont \$prefix/font.pf2 ; then
  set gfxmode=800x600
  insmod all_video
  insmod gfxterm
  terminal_output gfxterm
fi
set menu_color_normal=white/black
set menu_color_highlight=black/light-gray
menuentry "Installer le relais Paranoid (français) - EFFACE UN DISQUE" {
  linux /install.amd/vmlinuz $fr --- quiet
  initrd /install.amd/initrd.gz
}
menuentry "Install the Paranoid relay (English) - ERASES A DISK" {
  linux /install.amd/vmlinuz $en --- quiet
  initrd /install.amd/initrd.gz
}
menuentry "Redémarrer / Reboot" {
  reboot
}
EOF

cat >"$work/isolinux.cfg" <<EOF
# Relais Paranoid : aucune entrée par défaut (timeout 0 = attente sans délai).
path
include stdmenu.cfg
ui vesamenu.c32
menu title Paranoid relay $version
timeout 0
label fr
  menu label Installer le relais Paranoid (francais) - EFFACE UN DISQUE
  kernel /install.amd/vmlinuz
  append $fr initrd=/install.amd/initrd.gz --- quiet
label en
  menu label Install the Paranoid relay (English) - ERASES A DISK
  kernel /install.amd/vmlinuz
  append $en initrd=/install.amd/initrd.gz --- quiet
EOF

name="paranoid-relay-$version-amd64.iso"
rm -f "$out/$name"
xorriso -indev "$iso" -outdev "$out/$name" -boot_image any replay \
	-map "$work/grub.cfg" /boot/grub/grub.cfg \
	-map "$work/isolinux.cfg" /isolinux/isolinux.cfg \
	-map "$here/preseed.cfg" /paranoid/preseed.cfg \
	-map "$here/disk.sh" /paranoid/disk.sh \
	-map "$here/postinst.sh" /paranoid/postinst.sh \
	-map "$work/debs" /paranoid/debs
ls -l "$out/$name"
