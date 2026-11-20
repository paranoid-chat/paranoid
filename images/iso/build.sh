#!/bin/sh
# images/iso/build.sh VERSION DEB SORTIE
# Construit paranoid-relay-VERSION-amd64.iso dans SORTIE. À lancer dans un conteneur debian:trixie (root).
#   1. ISO netinst officielle de Debian 13 (somme SHA-256 vérifiée) : système de base hors ligne ;
#   2. /paranoid/debs : paranoid-relay et les paquets de packages.list avec leurs dépendances,
#      avec les mises à jour de sécurité (dépôt local sur l'ISO) ;
#   3. /paranoid/preseed.cfg, ask.sh (mot de passe root, disque, démarrage), late.sh, postinst.sh ;
#   4. menus de démarrage BIOS et UEFI remplacés : deux entrées, aucune par défaut, attente sans délai.
set -eu
version=$1 deb=$2 out=$3
here=$(cd "$(dirname "$0")" && pwd)
work=/tmp/paranoid-iso
rm -rf "$work"
mkdir -p "$work/debs" "$out"

export DEBIAN_FRONTEND=noninteractive
# Sources du conteneur (trixie + mises à jour de sécurité) : outils et paquets de l'ISO.
apt-get update -q
apt-get install -y -q --no-install-recommends xorriso curl ca-certificates openssl apt-utils debootstrap >/dev/null

base=https://cdimage.debian.org/debian-cd/current/amd64/iso-cd
curl -fsSL "$base/SHA256SUMS" -o "$work/SHA256SUMS"
netinst=$(grep -oE 'debian-[0-9.]+-amd64-netinst\.iso' "$work/SHA256SUMS" | head -1)
echo "ISO de base : $netinst"
curl -fsSL "$base/$netinst" -o "$work/$netinst"
(cd "$work" && grep " $netinst\$" SHA256SUMS | sha256sum -c -)

# Paquets du relais avec TOUTES leurs dépendances (et leurs alternatives), même celles déjà présentes
# dans le conteneur (curl, openssl…), sinon l'installation hors ligne échoue. Dépôt local (Packages) :
# dans le système installé, apt ne prend que ce qui manque.
deps=$(dpkg-deb -f "$deb" Depends | sed 's/([^)]*)//g; s/[,|]/ /g')
# shellcheck disable=SC2046,SC2086
pkgs=$(apt-cache depends --recurse --no-recommends --no-suggests --no-conflicts --no-breaks --no-replaces \
	--no-enhances $(cat "$here/packages.list") $deps | grep -v '^ ' | grep -v '^<' | sort -u)
# shellcheck disable=SC2086
(cd "$work/debs" && { apt-get download -q $pkgs >/dev/null 2>&1 ||
	for p in $pkgs; do apt-get download -q "$p" >/dev/null 2>&1 || echo "absent : $p"; done; })
cp "$deb" "$work/debs/"
cp "$here/packages.list" "$here/install.sh" "$work/debs/"
(cd "$work/debs" && apt-ftparchive packages . >Packages && apt-ftparchive release . >Release)
echo "Paquets ajoutés : $(find "$work/debs" -name '*.deb' | wc -l) ($(du -sh "$work/debs" | cut -f1))"

# Test : le dépôt local installe le relais sur un système de base trixie sans réseau.
debootstrap --variant=minbase trixie "$work/t" http://deb.debian.org/debian >/dev/null
cp -r "$work/debs" "$work/t/tmp/paranoid-debs"
rm -f "$work/t/etc/apt/sources.list" "$work/t/etc/apt/sources.list.d/"*
mount -t proc proc "$work/t/proc"
chroot "$work/t" sh /tmp/paranoid-debs/install.sh /tmp/paranoid-debs
umount "$work/t/proc"
chroot "$work/t" dpkg-query -W -f '${Package} ${Status}\n' paranoid-relay console-setup openssh-server nftables curl
[ "$(chroot "$work/t" dpkg-query -W -f '${Status}' paranoid-relay)" = "install ok installed" ]
rm -rf "$work/t"

iso=$work/$netinst
xorriso -osirrox on -indev "$iso" -extract /boot/grub/grub.cfg "$work/grub.orig" >/dev/null 2>&1
echo "----- grub.cfg d'origine (extrait) -----"
grep -nE 'menuentry|linux|initrd' "$work/grub.orig" | head -20
echo "----------------------------------------"
xorriso -indev "$iso" -find /install.amd -maxdepth 1 -exec lsdl 2>/dev/null | grep -E 'vmlinuz|initrd' ||
	{ echo "noyau de l'installeur introuvable" >&2; exit 1; }

common="auto=true priority=critical preseed/file=/cdrom/paranoid/preseed.cfg"
# Seule la variante latin9 du clavier français existe dans l'installeur (pc105.ekmap) : « fr » seul donne un QWERTY.
fr="$common locale=fr_FR.UTF-8 language=fr country=FR keymap=\"fr(latin9)\" paranoid/lang=fr"
en="$common locale=en_US.UTF-8 language=en country=US keymap=us paranoid/lang=en"

# Mot de passe root aléatoire de l'installeur (remplacé par celui que demande ask.sh).
hash=$(openssl passwd -6 "$(head -c 48 /dev/urandom | base64)")
sed "s|@ROOT_HASH@|$hash|" "$here/preseed.cfg" >"$work/preseed.cfg"

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
menuentry "Installer un relais Paranoid (Français)" {
  linux /install.amd/vmlinuz $fr --- quiet
  initrd /install.amd/initrd.gz
}
menuentry "Install a Paranoid relay (English)" {
  linux /install.amd/vmlinuz $en --- quiet
  initrd /install.amd/initrd.gz
}
EOF

# Menu BIOS (vesamenu) en CP437 : « ç » affiché correctement.
cat >"$work/isolinux.utf8" <<EOF
# Relais Paranoid : aucune entrée par défaut (timeout 0 = attente sans délai).
path
include stdmenu.cfg
ui vesamenu.c32
menu title Paranoid relay $version
timeout 0
label fr
  menu label Installer un relais Paranoid (Français)
  kernel /install.amd/vmlinuz
  append $fr initrd=/install.amd/initrd.gz --- quiet
label en
  menu label Install a Paranoid relay (English)
  kernel /install.amd/vmlinuz
  append $en initrd=/install.amd/initrd.gz --- quiet
EOF

iconv -f UTF-8 -t CP437 "$work/isolinux.utf8" >"$work/isolinux.cfg"

name="paranoid-relay-$version-amd64.iso"
rm -f "$out/$name"
xorriso -indev "$iso" -outdev "$out/$name" -boot_image any replay \
	-map "$work/grub.cfg" /boot/grub/grub.cfg \
	-map "$work/isolinux.cfg" /isolinux/isolinux.cfg \
	-map "$work/preseed.cfg" /paranoid/preseed.cfg \
	-map "$here/ask.sh" /paranoid/ask.sh \
	-map "$here/late.sh" /paranoid/late.sh \
	-map "$here/postinst.sh" /paranoid/postinst.sh \
	-map "$work/debs" /paranoid/debs
ls -l "$out/$name"
