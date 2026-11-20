#!/bin/sh
# Installeur Debian (preseed/late_command), système installé monté sur /target.
# Journal : /var/log/paranoid-install.log du système installé.
log=/target/var/log/paranoid-install.log
exec >>"$log" 2>&1
echo "== paquets du relais"
mkdir -p /target/tmp/paranoid-debs
cp /cdrom/paranoid/debs/*.deb /target/tmp/paranoid-debs/
# apt (et non dpkg -i) : il respecte l'ordre des pré-dépendances (python3, etc.).
in-target sh -c 'DEBIAN_FRONTEND=noninteractive apt-get install -y --no-download -o Dpkg::Options::=--force-confold /tmp/paranoid-debs/*.deb' ||
	echo "ÉCHEC de l'installation des paquets"
rm -rf /target/tmp/paranoid-debs
in-target dpkg -l paranoid-relay console-setup openssh-server nftables unattended-upgrades | tail -5

echo "== mot de passe root"
if [ -s /tmp/paranoid-root ]; then
	printf 'root:%s\n' "$(cat /tmp/paranoid-root)" | chroot /target chpasswd && echo "posé"
	rm -f /tmp/paranoid-root
else
	echo "ABSENT"
fi

echo "== langue de l'assistant"
lang=fr
grep -q 'paranoid/lang=en' /proc/cmdline && lang=en
mkdir -p /target/etc/paranoid-relay
echo "LANG_UI=$lang" >/target/etc/paranoid-relay/firstboot

echo "== réglages du système"
cp /cdrom/paranoid/postinst.sh /target/tmp/paranoid-postinst.sh
in-target sh /tmp/paranoid-postinst.sh || echo "ÉCHEC de postinst.sh"
rm -f /target/tmp/paranoid-postinst.sh
exit 0
