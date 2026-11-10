# Relais de messagerie (v0)

Implémentation de `../PROTOCOL.md` : boîtes aux lettres chiffrées de bout en bout, sans compte, sans journal d'accès. SQLite en Go pur (`modernc.org/sqlite`), donc un binaire statique sans CGO.

## Fichiers

| Fichier | Rôle |
|---|---|
| `main.go` | Démarrage TLS 1.3, purge périodique, arrêt propre, affichage de l'empreinte du certificat |
| `server.go` | Routes HTTP, authentification Ed25519, anti-rejeu, limitation de débit |
| `store.go` | Base SQLite : boîtes, messages, quota, TTL, expiration des boîtes |
| `server_test.go` | Tests HTTP de bout en bout (cycle complet, quota, auth, purge, limites) |
| `limiter_test.go` | Tests du limiteur de débit et du cache de nonces |

## 1. Compiler (sur ton PC)

Installe Go 1.24 ou plus récent (go.dev/dl), puis dans le dossier des sources, en PowerShell :

```powershell
go mod tidy          # télécharge modernc.org/sqlite et crée go.sum
go test ./...        # tous les tests doivent passer
$env:CGO_ENABLED="0"; $env:GOOS="linux"; $env:GOARCH="amd64"
go build -trimpath -ldflags "-s -w" -o relayd .
```

Si `go test` échoue, garde la sortie exacte et envoie-la.

## 2. Copier sur le serveur

Avec `pscp` (livré avec PuTTY) et ta clé `.ppk` :

```powershell
pscp -i C:\chemin\cle.ppk relayd matteo@192.168.1.252:/tmp/relayd
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

Code dans `cmd/relaycli`, formats de message dans `../MESSAGES.md`. Les messages sont chiffrés avec le Double Ratchet (`dr.go`, format v2). Le client ne dépend que de la bibliothèque standard de Go.

Si une session se désynchronise, les deux côtés font `relaycli reset NOM`.

```bash
go build -o relaycli ./cmd/relaycli
./relaycli help
```

Chaque identité vit dans un dossier (`-d alice`). Le fichier `state.json` contient les clés privées **en clair** : c'est un outil de test, à ne pas utiliser pour de vrais échanges.

## Notifications (UnifiedPush)

Activées par défaut (voir `../PROTOCOL.md` §6 bis). Options :

| Option | Rôle |
|---|---|
| `--push=false` | désactive les réveils |
| `--vapid FICHIER` | clé VAPID (par défaut `vapid.pem` à côté de la base, créée au premier démarrage, mode 0600) |
| `--vapid-sub mailto:…` | contact VAPID transmis aux services de notification |
| `--push-allow-private` | autorise un service de notification sur un réseau privé (ntfy en LAN) |
| `--push-allow-http` | autorise le http (tests uniquement) |

## Limites connues de cette v0

- Les limites de débit et les nonces sont en mémoire : ils sont remis à zéro au redémarrage.
- L'IP vue par le relais est celle de la connexion directe. Derrière un reverse proxy, la limitation par IP serait faussée.
- Pas de preuve de travail ni de jeton pour la création de boîtes.
