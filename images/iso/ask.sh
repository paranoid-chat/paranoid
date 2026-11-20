#!/bin/sh
# Installeur Debian (partman/early_command) : les seules questions de l'installation.
#   1. mot de passe root (case « afficher en clair » de l'installeur), 14 caractères et 3 types ;
#   2. disque où installer (le support d'installation n'est pas proposé) ;
#   3. en BIOS : démarrage sur ce disque, ou sur un autre choisi dans une liste.
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
Description: Disk to install the Paranoid relay on
 Everything on the chosen disk will be erased.
Description-fr.UTF-8: Disque où installer le relais Paranoid
 Tout le contenu du disque choisi sera effacé.

Template: paranoid/boot-main
Type: boolean
Default: true
Description: Install the boot loader on the main disk (${DISK})?
Description-fr.UTF-8: Installer le démarrage sur le disque principal (${DISK}) ?

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

ask() { # ask QUESTION : affiche une question (toujours, même déjà vue)
	db_fset "$1" seen false
	db_input critical "$1" || true
	db_go
}

# ---------- 1. mot de passe root ----------
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

# ---------- 2. disque ----------
# Disque du support d'installation (clé USB) : jamais proposé.
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

# ---------- 3. démarrage (BIOS seulement ; en UEFI, partition EFI du disque choisi) ----------
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
# Partitionnement automatique de ce disque, chargeur de démarrage sans question.
echo "d-i partman-auto/disk string $disk" | debconf-set-selections
echo "d-i grub-installer/bootdev string $boot" | debconf-set-selections
db_set partman-auto/disk "$disk" && db_fset partman-auto/disk seen true
if db_set grub-installer/bootdev "$boot"; then
	db_fset grub-installer/bootdev seen true
else
	logger -t paranoid "grub-installer/bootdev pas encore chargé : debconf-set-selections seul"
fi
exit 0
