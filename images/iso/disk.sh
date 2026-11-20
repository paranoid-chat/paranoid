#!/bin/sh
# Installeur Debian (partman/early_command) : choix du disque puis confirmation tapée
# (EFFACER ou ERASE) avant tout effacement. Toute autre réponse ramène au choix du disque.
. /usr/share/debconf/confmodule
db_capb backup

cat >/tmp/paranoid.templates <<'EOF'
Template: paranoid/disk
Type: select
Choices: ${CHOICES}
Description: Disk to install the Paranoid relay on
 Everything on the chosen disk will be erased. The installation medium is not listed.
Description-fr.UTF-8: Disque où installer le relais Paranoid
 Tout le contenu du disque choisi sera effacé. Le support d'installation n'est pas proposé.

Template: paranoid/confirm
Type: string
Description: Type ERASE to erase ${DISK}
 The whole disk will be erased and the Paranoid relay installed on it. This cannot be undone. Any other answer goes back to the disk choice.
Description-fr.UTF-8: Tapez EFFACER pour effacer ${DISK}
 Tout le disque sera effacé et le relais Paranoid y sera installé. C'est définitif. Toute autre réponse ramène au choix du disque.

Template: paranoid/nodisk
Type: error
Description: No disk found
 No disk is available to install the Paranoid relay. Add a disk, then restart the installation.
Description-fr.UTF-8: Aucun disque trouvé
 Aucun disque n'est disponible pour installer le relais Paranoid. Ajouter un disque puis relancer l'installation.
EOF
debconf-loadtemplate paranoid /tmp/paranoid.templates

# Disque du support d'installation (clé USB) : jamais proposé.
media=$(awk '$2 == "/cdrom" { print $1; exit }' /proc/mounts)
case $media in
/dev/nvme*p[0-9]* | /dev/mmcblk*p[0-9]*) media=${media%p[0-9]*} ;;
/dev/*[0-9]) media=$(echo "$media" | sed 's/[0-9]*$//') ;;
esac

while :; do
	choices=""
	for d in $(list-devices disk); do
		[ "$d" = "$media" ] && continue
		b=${d#/dev/}
		size=$(($(cat "/sys/block/$b/size" 2>/dev/null || echo 0) / 2097152))
		model=$(cat "/sys/block/$b/device/model" 2>/dev/null | tr -d ',' | sed 's/ *$//')
		item="$d - $size GiB${model:+ - $model}"
		choices="${choices:+$choices, }$item"
	done
	if [ -z "$choices" ]; then
		db_fset paranoid/nodisk seen false
		db_input critical paranoid/nodisk || true
		db_go || true
		exit 1
	fi
	db_subst paranoid/disk CHOICES "$choices"
	db_fset paranoid/disk seen false
	db_input critical paranoid/disk || true
	db_go || continue
	db_get paranoid/disk
	item=$RET
	disk=${item%% *}

	db_subst paranoid/confirm DISK "$item"
	db_set paranoid/confirm ""
	db_fset paranoid/confirm seen false
	db_input critical paranoid/confirm || true
	db_go || continue
	db_get paranoid/confirm
	case $RET in
	EFFACER | ERASE) break ;;
	esac
done

# Disque choisi : partitionnement automatique et chargeur de démarrage sur ce disque.
echo "d-i partman-auto/disk string $disk" | debconf-set-selections
echo "d-i grub-installer/bootdev string $disk" | debconf-set-selections
db_set partman-auto/disk "$disk" 2>/dev/null || true
db_fset partman-auto/disk seen true 2>/dev/null || true
exit 0
