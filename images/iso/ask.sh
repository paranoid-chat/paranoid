#!/bin/sh
. /usr/share/debconf/confmodule
db_capb backup

cat >/tmp/paranoid.templates <<'EOF'
Template: paranoid/title-users
Type: title
Description: Users
Description-fr.UTF-8: Création utilisateurs

Template: paranoid/title-disk
Type: title
Description: Partition disks
Description-fr.UTF-8: Partitionner les disques

Template: paranoid/root-password
Type: password
Description: Password of the superuser "root":
Description-fr.UTF-8: Mot de passe du superutilisateur "root" :

Template: paranoid/root-password-again
Type: password
Description: Confirm the password of the superuser "root":
Description-fr.UTF-8: Confirmation du mot de passe superutilisateur "root" :

Template: paranoid/mismatch
Type: error
Description: The two passwords are different.
Description-fr.UTF-8: Les deux mots de passe sont différents.

Template: paranoid/weak
Type: error
Description: Password too weak
 At least 14 characters, 3 kinds among lowercase, uppercase, digits, symbols.
Description-fr.UTF-8: Mot de passe trop faible
 14 caractères minimum, 3 types parmi minuscules, majuscules, chiffres, symboles.

Template: paranoid/disk
Type: select
Choices: ${CHOICES}
Description: Everything on the chosen disk will be erased.
 Disk to install the Paranoid relay on
Description-fr.UTF-8: Tout le contenu du disque choisi sera effacé.
 Disque où installer le relais Paranoid

Template: paranoid/boot-main
Type: boolean
Default: true
Description: Install GRUB (boot partition) on the main disk? (${DISK})
Description-fr.UTF-8: Installer GRUB (partition de démarrage) sur le disque principal ? (${DISK})

Template: paranoid/boot-disk
Type: select
Choices: ${CHOICES}
Description: Boot disk
Description-fr.UTF-8: Disque de démarrage

Template: paranoid/nodisk
Type: error
Description: No disk found
Description-fr.UTF-8: Aucun disque trouvé
EOF
debconf-loadtemplate paranoid /tmp/paranoid.templates

db_get debian-installer/language
unit=GB
case $RET in fr*) unit=Go ;; esac

strong() {
	p=$1
	[ "${#p}" -ge 14 ] || return 1
	c=0
	case $p in *[a-z]*) c=$((c + 1)) ;; esac
	case $p in *[A-Z]*) c=$((c + 1)) ;; esac
	case $p in *[0-9]*) c=$((c + 1)) ;; esac
	case $p in *[!a-zA-Z0-9]*) c=$((c + 1)) ;; esac
	[ $c -ge 3 ]
}

ask() {
	db_fset "$1" seen false
	db_input critical "$1" || true
	db_go
}

db_settitle paranoid/title-users
while :; do
	db_set paranoid/root-password ""
	db_set paranoid/root-password-again ""
	ask paranoid/root-password || continue
	db_get paranoid/root-password
	p1=$RET
	[ -n "$p1" ] || continue
	ask paranoid/root-password-again || continue
	db_get paranoid/root-password-again
	if [ "$p1" != "$RET" ]; then
		ask paranoid/mismatch || true
	elif ! strong "$p1"; then
		ask paranoid/weak || true
	else
		break
	fi
done
umask 077
printf '%s' "$p1" >/tmp/paranoid-root
p1=""
db_set paranoid/root-password ""
db_set paranoid/root-password-again ""

media=$(awk '$2 == "/cdrom" { print $1; exit }' /proc/mounts)
case $media in
/dev/nvme*p[0-9]* | /dev/mmcblk*p[0-9]*) media=${media%p[0-9]*} ;;
/dev/*[0-9]) media=$(echo "$media" | sed 's/[0-9]*$//') ;;
esac

choices=""
for d in $(list-devices disk); do
	[ "$d" = "$media" ] && continue
	b=${d#/dev/}
	size=$(($(cat "/sys/block/$b/size" 2>/dev/null || echo 0) / 2097152))
	model=$(cat "/sys/block/$b/device/model" 2>/dev/null | tr -d ',' | sed 's/ *$//')
	choices="${choices:+$choices, }$d - $size $unit${model:+ - $model}"
done
db_settitle paranoid/title-disk
if [ -z "$choices" ]; then
	ask paranoid/nodisk || true
	exit 1
fi
db_subst paranoid/disk CHOICES "$choices"
until ask paranoid/disk; do :; done
db_get paranoid/disk
disk=${RET%% *}
boot=$disk

if [ ! -d /sys/firmware/efi ]; then
	db_subst paranoid/boot-main DISK "$disk"
	until ask paranoid/boot-main; do :; done
	db_get paranoid/boot-main
	if [ "$RET" = false ]; then
		db_subst paranoid/boot-disk CHOICES "$choices"
		until ask paranoid/boot-disk; do :; done
		db_get paranoid/boot-disk
		boot=${RET%% *}
	fi
fi

logger -t paranoid "disque $disk, démarrage $boot"
echo "d-i partman-auto/disk string $disk" | debconf-set-selections
echo "d-i grub-installer/bootdev string $boot" | debconf-set-selections
db_set partman-auto/disk "$disk" && db_fset partman-auto/disk seen true
if db_set grub-installer/bootdev "$boot"; then
	db_fset grub-installer/bootdev seen true
else
	logger -t paranoid "grub-installer/bootdev pas encore chargé : debconf-set-selections seul"
fi
exit 0
