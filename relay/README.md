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
