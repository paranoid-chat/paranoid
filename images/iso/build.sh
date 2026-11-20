#!/bin/sh
# images/iso/build.sh VERSION DEB SORTIE
# Construit paranoid-relay-VERSION-amd64.iso dans SORTIE. À lancer dans un conteneur debian:trixie (root).
#   1. simple-cdd : installeur Debian 13 hors ligne avec le profil « paranoid » (paquets, preseed, postinst)
#      et le paquet paranoid-relay ;
#   2. xorriso : menus de démarrage BIOS et UEFI remplacés (deux entrées, aucune par défaut, attente
#      sans délai) et ajout de /paranoid/disk.sh (choix du disque + confirmation tapée).
set -eu
version=$1 deb=$2 out=$3
here=$(cd "$(dirname "$0")" && pwd)

export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q simple-cdd distro-info-data xorriso ca-certificates >/dev/null

work=/tmp/paranoid-iso
rm -rf "$work"
mkdir -p "$work/profiles" "$work/local" "$out"
cp "$here"/profiles/* "$work/profiles/"
cp "$deb" "$work/local/"
cd "$work"

# Trace des téléchargements du miroir (diagnostic en cas d'erreur 404).
f=$(python3 -c 'import simple_cdd.tools.mirror_download as m; print(m.__file__)')
sed -i 's/^\([[:space:]]*\)request.urlretrieve(url, filename=output)/\1print("GET", url, flush=True)\n&/' "$f"

build-simple-cdd --force-root --verbose --dist trixie \
	--profiles paranoid --auto-profiles paranoid \
	--local-packages "$work/local" \
	--debian-mirror http://deb.debian.org/debian/ \
	--security-mirror http://security.debian.org/debian-security/

iso=$(find "$work/images" -name '*.iso' | head -1)
[ -n "$iso" ] || { echo "aucune ISO produite" >&2; exit 1; }
echo "ISO simple-cdd : $iso"

# Paramètres de l'entrée « Install » d'origine (preseed et profil simple-cdd compris).
xorriso -osirrox on -indev "$iso" -extract /boot/grub/grub.cfg "$work/grub.orig" >/dev/null 2>&1
echo "----- grub.cfg d'origine -----"
cat "$work/grub.orig"
echo "------------------------------"
args=$(grep -m1 -E '^[[:space:]]*linux[[:space:]]+/install\.amd/vmlinuz' "$work/grub.orig" |
	sed -E 's/^[[:space:]]*linux[[:space:]]+[^[:space:]]+[[:space:]]*//; s/(^| )---( |$).*//; s/(^| )vga=[^ ]*//g')
echo "Paramètres repris : $args"
xorriso -indev "$iso" -find /install.amd -maxdepth 1 -exec lsdl 2>/dev/null | grep -E 'vmlinuz|initrd' ||
	{ echo "noyau de l'installeur introuvable" >&2; exit 1; }

fr="$args locale=fr_FR.UTF-8 language=fr country=FR keymap=fr"
en="$args locale=en_US.UTF-8 language=en country=US keymap=us"

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
xorriso -indev "$iso" -outdev "$out/$name" -boot_image any replay \
	-map "$work/grub.cfg" /boot/grub/grub.cfg \
	-map "$work/isolinux.cfg" /isolinux/isolinux.cfg \
	-map "$here/disk.sh" /paranoid/disk.sh
ls -l "$out/$name"
