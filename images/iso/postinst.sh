#!/bin/sh
# Exécuté dans le système installé (preseed/late_command), après l'installation des paquets du relais.
set -e

# Sources APT en ligne : l'installation s'est faite depuis l'ISO, sans réseau.
rm -f /etc/apt/sources.list
cat >/etc/apt/sources.list.d/debian.sources <<'SRC'
Types: deb
URIs: http://deb.debian.org/debian
Suites: trixie trixie-updates
Components: main non-free-firmware
Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg

Types: deb
URIs: http://security.debian.org/debian-security
Suites: trixie-security
Components: main non-free-firmware
Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg
SRC

# Correctifs de sécurité automatiques.
cat >/etc/apt/apt.conf.d/20auto-upgrades <<'APT'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT

# IPv6 désactivé (le relais et le pare-feu ne servent qu'IPv4).
printf 'net.ipv6.conf.all.disable_ipv6 = 1\nnet.ipv6.conf.default.disable_ipv6 = 1\n' >/etc/sysctl.d/90-paranoid-relay.conf

# SSH coupé jusqu'au choix fait dans l'assistant du premier démarrage.
systemctl disable ssh.service >/dev/null 2>&1 || true

# Assistant du premier démarrage (le fichier firstboot, avec la langue, est posé par late.sh).
systemctl enable paranoid-relay-firstboot.service
