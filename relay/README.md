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
| `/usr/sbin/paranoid-relay` | Administration : `config`, `info`, `status`, `apply`, `import`, `newcert` |
| `paranoid-relay.service` | Service durci : utilisateur système `paranoid-relay`, seule capacité `CAP_NET_BIND_SERVICE` (port 443 possible), système en lecture seule sauf `/var/lib/paranoid-relay`, filtre d'appels système (`systemd-analyze security` : 1.5) |
| `paranoid-relay-firewall.service` | Charge `/etc/paranoid-relay/firewall.nft` (table `inet paranoid_relay`, séparée des règles existantes), rechargée après `nftables.service` |
| `/etc/paranoid-relay/relay.conf` | Configuration écrite par l'assistant |
| `/etc/paranoid-relay/relay.crt`, `relay.key` | Certificat auto-signé ECDSA P-256 généré à l'installation (clé `0640 root:paranoid-relay`) |
| `/var/lib/paranoid-relay/relay.db` | Base SQLite |

Installation et configuration, en root :

```bash
apt install ./paranoid-relay_1.0.0_amd64.deb
paranoid-relay config
```

L'assistant (français ou anglais) demande le port local, l'adresse et le port publics, `--max-db` et `--min-free`, le mode du pare-feu et l'accès SSH, puis affiche l'URL et l'empreinte à donner aux utilisateurs (`paranoid-relay info` les réaffiche).

- **Pare-feu** : `ratelimit` limite seulement le port du relais (10 nouvelles connexions/s, 32 simultanées par IP), `full` bloque aussi tout le reste sauf SSH, `off` ne charge aucune règle.
- **SSH** : `keep` ne touche à rien ; `key` (clé uniquement) et `password` (mot de passe de 14 caractères minimum, 3 types de caractères) écrivent `/etc/ssh/sshd_config.d/00-paranoid-relay.conf` : root interdit, un seul compte autorisé, 3 essais par connexion, 30 s pour s'authentifier, pas de redirections. Le pare-feu bloque 15 minutes une IP qui ouvre plus de 4 connexions SSH par minute. `off` désactive SSH (refusé si l'assistant tourne en SSH).
- **Retour arrière** : lancé en SSH, l'assistant demande d'ouvrir une nouvelle connexion puis de taper `OK` dans les 120 s ; sans réponse, `relay.conf`, le pare-feu et SSH reviennent à leur état précédent.
- `apt remove` arrête le relais et garde clé et base ; `apt purge` les efface (l'empreinte est perdue).

### Migrer le relais installé à la main vers le paquet

L'empreinte reste la même : les téléphones ne voient aucune différence. En root sur `<vm-relais>` :

```bash
apt install ./paranoid-relay_1.0.0_amd64.deb
systemctl stop relay
paranoid-relay import /etc/relay/relay.crt /etc/relay/relay.key /var/lib/relay/relay.db
paranoid-relay config        # port 8443, adresse <IP_PUBLIQUE>, port public 443
paranoid-relay status
systemctl disable relay
```

Retour à l'ancien relais si besoin : `systemctl stop paranoid-relay && systemctl start relay`.

## 1. Compiler (sur ton PC)

La CI compile tout (voir le `README.md` principal). Pour compiler à la main : Go 1.26 ou plus récent (go.dev/dl), puis dans ce dossier, en PowerShell :

```powershell
go mod tidy          # télécharge modernc.org/sqlite et crée go.sum
go test ./...        # tous les tests doivent passer
$env:CGO_ENABLED="0"; $env:GOOS="linux"; $env:GOARCH="amd64"
go build -trimpath -ldflags "-s -w" -o relayd .
```

Si `go test` échoue, garde la sortie exacte et envoie-la.

## 2. Copier sur le serveur

OpenSSH est inclus dans Windows :

```powershell
scp .\relayd <utilisateur>@192.168.1.252:/tmp/relayd
```

## 3. Installer et démarrer (sur le serveur, en `su -`)

```bash
install -o root -g root -m 0755 /tmp/relayd /opt/relay/relayd
rm /tmp/relayd
systemctl daemon-reload
systemctl enable --now relay
systemctl status relay --no-pager
journalctl -u relay -n 10 --no-pager
```

Le journal doit afficher une ligne « relais à l'écoute sur :8443, empreinte SHA-256 : … ». Cette empreinte doit être identique à celle donnée par `openssl x509 -in /etc/relay/relay.crt -noout -fingerprint -sha256`.

## 4. Vérifier

Sur le serveur : `ss -tlnp | grep 8443`.

Depuis ton PC (`-k` ignore la vérification du certificat auto-signé, uniquement pour ce test) :

```powershell
curl.exe -k -i https://192.168.1.252:8443/v1/box/AAAAAAAAAAAAAAAAAAAAAA/msgs
```

Réponse attendue : `404` avec le texte « boîte inconnue ». Cela prouve que TLS et le service fonctionnent.

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
