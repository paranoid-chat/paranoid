#!/bin/sh
log=/target/var/log/paranoid-install.log
exec >>"$log" 2>&1
echo "== paquets du relais"
cp -r /cdrom/paranoid/debs /target/tmp/paranoid-debs
in-target sh /tmp/paranoid-debs/install.sh /tmp/paranoid-debs || echo "ÉCHEC de l'installation des paquets"
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
