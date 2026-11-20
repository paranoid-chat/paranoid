# Relais Paranoid

Implémentation de `../PROTOCOL.md` : boîtes aux lettres chiffrées de bout en bout, sans compte, sans journal d'accès. SQLite en Go pur (`modernc.org/sqlite`), donc un binaire statique sans CGO.

## Fichiers

| Fichier | Rôle |
|---|---|
| `main.go` | Démarrage TLS 1.3, purge périodique, arrêt propre, affichage de l'empreinte du certificat |
| `server.go` | Routes HTTP, authentification Ed25519, anti-rejeu, limitation de débit |
| `store.go` | Base SQLite : boîtes, messages, quota, TTL, expiration des boîtes |
| `server_test.go` | Tests HTTP de bout en bout (cycle complet, quota, auth, purge, limites) |
| `limiter_test.go` | Tests du limiteur de débit et du cache de nonces |

## Paquet `.deb` (Debian 12 et 13)

La CI construit `paranoid-relay_VERSION_amd64.deb` et `_arm64.deb` (artefact « relais-binaires »). Sources dans `packaging/` : `build-deb.sh`, scripts `debian/`, arborescence `root/`, version dans `packaging/VERSION`.

| Contenu | Rôle |
|---|---|
| `/usr/bin/relayd` | Binaire statique |
| `/usr/sbin/paranoid-relay` | Administration : `config`, `info`, `status`, `apply`, `import`, `newcert`, `announce [remove]`, `firstboot` (images seulement) |
| `paranoid-relay.service` | Service durci : utilisateur système `paranoid-relay`, seule capacité `CAP_NET_BIND_SERVICE` (port 443 possible), système en lecture seule sauf `/var/lib/paranoid-relay`, filtre d'appels système (`systemd-analyze security` : 1.5) |
| `paranoid-relay-announce.timer` | Toutes les heures (et 5 min après le démarrage), si `ANNOUNCE=yes` : `paranoid-relay announce` en utilisateur `paranoid-relay`, service durci séparé de `relayd` |
| `paranoid-relay-firewall.service` | Charge `/etc/paranoid-relay/firewall.nft` (table `inet paranoid_relay`, séparée des règles existantes), rechargée après `nftables.service` |
| `/etc/paranoid-relay/relay.conf` | Configuration écrite par l'assistant |
| `/etc/paranoid-relay/relay.crt`, `relay.key` | Certificat auto-signé ECDSA P-256 généré à l'installation (clé `0640 root:paranoid-relay`) |
| `/var/lib/paranoid-relay/relay.db` | Base SQLite |

Installation et configuration, en root :

```bash
apt install ./paranoid-relay_1.1.2_amd64.deb
paranoid-relay config
```

L'assistant (français ou anglais) demande le port local, l'adresse et le port publics, `--max-db` et `--min-free`, le mode du pare-feu et l'accès SSH, puis affiche l'URL et l'empreinte à donner aux utilisateurs (`paranoid-relay info` les réaffiche).

- **Pare-feu** : `ratelimit` limite seulement le port du relais (10 nouvelles connexions/s, 32 simultanées par IP), `full` bloque aussi tout le reste sauf SSH, `off` ne charge aucune règle.
- **SSH** : `keep` ne touche à rien ; `key` (clé uniquement) et `password` (mot de passe de 14 caractères minimum, 3 types de caractères) écrivent `/etc/ssh/sshd_config.d/00-paranoid-relay.conf` : root interdit, un seul compte autorisé, 3 essais par connexion, 30 s pour s'authentifier, pas de redirections. Le pare-feu bloque 15 minutes une IP qui ouvre plus de 4 connexions SSH par minute. `off` désactive SSH (refusé si l'assistant tourne en SSH).
- **Retour arrière** : lancé en SSH, l'assistant demande d'ouvrir une nouvelle connexion puis de taper `OK` dans les 120 s ; sans réponse, `relay.conf`, le pare-feu et SSH reviennent à leur état précédent.
- **Annuaire public** : l'assistant propose (Oui par défaut sur une nouvelle installation, Non pour un relais mis à jour depuis 1.0.0) de publier le relais sur getparanoid.fr, avec un nom au choix. Sont publiés : nom, adresse et port publics, IP d'envoi, empreinte, version, heure du dernier signal. Le signal est signé par la clé TLS du relais ; le site vérifie la signature et que l'adresse publique correspond à l'IP d'envoi (détails dans `../site/README.md`). À la fin de l'assistant, le premier signal part tout de suite et le résultat s'affiche (« enregistré : … » ou l'erreur) ; ensuite, le timer l'envoie toutes les heures sans intervention. Répondre Non retire le relais de l'annuaire. `relayd` lui-même ne fait toujours aucune requête sortante.
- `apt remove` retire le relais de l'annuaire, l'arrête et garde clé et base ; `apt purge` les efface (l'empreinte est perdue).

## Images prêtes à l'emploi (ISO amd64, Raspberry Pi)

Workflow `.github/workflows/images.yml` (sources dans `images/`) : à chaque nouvelle version de `relay/packaging/VERSION`, une release **`relay-vVERSION`** (jamais « latest » : le lien de l'APK ne change pas) avec :

| Fichier | Pour |
|---|---|
| `paranoid-relay-VERSION-amd64.iso` | PC ou VM amd64, BIOS ou UEFI (Secure Boot compris : chargeur Debian signé) |
| `paranoid-relay-VERSION-raspberrypi-arm64.img.xz` | Raspberry Pi 3, 4, 5, 400, Zero 2 W (carte SD de 8 Go minimum, 16 Go conseillés) |
| `paranoid-relay_VERSION_amd64.deb`, `_arm64.deb` + `SHA256SUMS` | Debian existante |

Les deux images se construisent en parallèle et sont envoyées dans une release brouillon, publiée quand tout est prêt (pas d'artefacts GitHub : ils dépasseraient le quota). `workflow_dispatch` avec « Remplacer » reconstruit une version déjà publiée.

**ISO** (`images/iso/build.sh`, conteneur `debian:trixie`) : remaster de la netinst officielle de Debian 13 (somme SHA-256 vérifiée), installation **sans réseau**.

- `/paranoid/debs` : dépôt local (`Packages`) avec `paranoid-relay`, les paquets de `packages.list` et toutes leurs dépendances (mises à jour de sécurité comprises). La construction vérifie qu'il installe le relais sans réseau sur un debootstrap trixie.
- Clavier : `fr(latin9)` en français, seule variante française présente dans l'installeur Debian 13 (`fr` seul donne un QWERTY).
- Menus BIOS et UEFI remplacés : *Installer un relais Paranoid (Français)* / *Install a Paranoid relay (English)*, aucune entrée par défaut, attente sans délai.
- `preseed.cfg` : installation automatique, réseau désactivé, root seul (aucun autre compte), partitionnement automatique, puis `late.sh` (`install.sh` : apt depuis le dépôt local, mot de passe root, langue pour l'assistant, `postinst.sh` : sources APT en ligne, `unattended-upgrades`, IPv6 désactivé, SSH coupé, assistant activé ; journal dans `/var/log/paranoid-install.log`).
- `ask.sh` (`partman/early_command`) : mot de passe root (14 caractères minimum, 3 types), disque (le support d'installation est exclu), et en BIOS le disque de démarrage (principal ou au choix).
- Le certificat TLS est généré pendant l'installation, sur la machine.

**Raspberry Pi** (`images/pi/build.sh`, root sur le runner, émulation arm64 par binfmt) : Raspberry Pi OS Lite 64 bits (dernière version, somme vérifiée), paquet installé dans un chroot, `userconfig.service` masqué (pas de création de compte de Raspberry Pi OS), cloud-init limité à l'agrandissement (`users: []`, réseau et nom non gérés), SSH coupé. Le certificat n'est jamais dans l'image : il est créé au premier démarrage.

**Premier démarrage** (les deux images) : `paranoid-relay-firstboot.service` (présent si `/etc/paranoid-relay/firstboot` existe) lance `paranoid-relay firstboot` sur l'écran principal (tty1), avant l'invite de connexion. Il faut un écran et un clavier.

1. Langue : reprise du menu de démarrage sur l'ISO, demandée sur le Raspberry Pi (le clavier suit : AZERTY pour le français, QWERTY US pour l'anglais).
2. Nom du système.
3. IP fixe (pas de DHCP) : adresse, masque (`255.255.255.0` ou `24`), passerelle, DNS ; `ifupdown` sur l'ISO, NetworkManager sur le Raspberry Pi.
4. Raspberry Pi seulement : mot de passe root (14 caractères minimum, 3 types). Seul compte : root.
5. L'assistant du relais : port, adresse publique (proposée d'après `getparanoid.fr/relais.php?format=myip`), port public, plafonds, pare-feu (`full` par défaut), SSH (désactivé par défaut, ou clé pour root : `PermitRootLogin prohibit-password`), annuaire.
6. Écran final : URL, empreinte et port à ouvrir sur le routeur/box. URL et empreinte restent affichées au-dessus de l'invite de connexion (`/etc/issue.d/paranoid-relay.issue`).

*Annuler* à n'importe quelle étape recommence l'assistant au début. Une fois terminé, il ne se relance plus (`paranoid-relay config` reste disponible).

## Compiler à la main (sur ton PC)

La CI compile tout (voir le `README.md` principal). Pour compiler à la main : Go 1.26 ou plus récent (go.dev/dl), puis dans ce dossier, en PowerShell :

```powershell
go mod tidy          # télécharge modernc.org/sqlite et crée go.sum
go test ./...        # tous les tests doivent passer
$env:CGO_ENABLED="0"; $env:GOOS="linux"; $env:GOARCH="amd64"
go build -trimpath -ldflags "-s -w" -o relayd .
```

Si `go test` échoue, garde la sortie exacte et envoie-la.

Pour tester le binaire sans le paquet : `./relayd --listen :8443 --cert relay.crt --key relay.key --db relay.db`, puis `curl -k -i https://127.0.0.1:8443/v1/box/AAAAAAAAAAAAAAAAAAAAAA/msgs` doit répondre `404` « boîte inconnue ». Pour construire le paquet : `packaging/build-deb.sh amd64 ./relayd dist`.

## Paramètres

Les valeurs du protocole sont dans `DefaultConfig()` (`server.go`) : blob 2048 octets, 100 messages en attente, TTL 7 jours, expiration des boîtes à 90 jours, tolérance d'horodatage ±60 s, 10 créations de boîte par heure et par IP, 60 dépôts par minute et par boîte.

## Client de test `relaycli`

Code dans `cmd/relaycli`, formats dans `../MESSAGES.md` : appairage en un seul scan (`pair.go`, bloc version 3) et Double Ratchet (`dr.go`, bloc version 2). La crypto n'utilise que la bibliothèque standard de Go ; `go-qrcode` sert à afficher les QR.

Si une session se désynchronise, les deux côtés font `relaycli reset NOM`.

```bash
go build -o relaycli ./cmd/relaycli
./relaycli help
```

Chaque identité vit dans un dossier (`-d alice`). Le fichier `state.json` contient les clés privées **en clair** : c'est un outil de test, à ne pas utiliser pour de vrais échanges.

## Limites connues

- Les limites de débit et les nonces sont en mémoire : ils sont remis à zéro au redémarrage.
- L'IP vue par le relais est celle de la connexion directe. Derrière un reverse proxy, la limitation par IP serait faussée.
- Pas de preuve de travail ni de jeton pour la création de boîtes.
