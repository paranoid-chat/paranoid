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
| `/usr/sbin/paranoid-relay` | Administration : `config`, `info`, `status`, `apply`, `import`, `newcert`, `announce [remove]` |
| `paranoid-relay.service` | Service durci : utilisateur système `paranoid-relay`, seule capacité `CAP_NET_BIND_SERVICE` (port 443 possible), système en lecture seule sauf `/var/lib/paranoid-relay`, filtre d'appels système (`systemd-analyze security` : 1.5) |
| `paranoid-relay-announce.timer` | Toutes les heures (et 5 min après le démarrage), si `ANNOUNCE=yes` : `paranoid-relay announce` en utilisateur `paranoid-relay`, service durci séparé de `relayd` |
| `paranoid-relay-firewall.service` | Charge `/etc/paranoid-relay/firewall.nft` (table `inet paranoid_relay`, séparée des règles existantes), rechargée après `nftables.service` |
| `/etc/paranoid-relay/relay.conf` | Configuration écrite par l'assistant |
| `/etc/paranoid-relay/relay.crt`, `relay.key` | Certificat auto-signé ECDSA P-256 généré à l'installation (clé `0640 root:paranoid-relay`) |
| `/var/lib/paranoid-relay/relay.db` | Base SQLite |

Installation et configuration, en root :

```bash
apt install ./paranoid-relay_1.1.0_amd64.deb
paranoid-relay config
```

L'assistant (français ou anglais) demande le port local, l'adresse et le port publics, `--max-db` et `--min-free`, le mode du pare-feu et l'accès SSH, puis affiche l'URL et l'empreinte à donner aux utilisateurs (`paranoid-relay info` les réaffiche).

- **Pare-feu** : `ratelimit` limite seulement le port du relais (10 nouvelles connexions/s, 32 simultanées par IP), `full` bloque aussi tout le reste sauf SSH, `off` ne charge aucune règle.
- **SSH** : `keep` ne touche à rien ; `key` (clé uniquement) et `password` (mot de passe de 14 caractères minimum, 3 types de caractères) écrivent `/etc/ssh/sshd_config.d/00-paranoid-relay.conf` : root interdit, un seul compte autorisé, 3 essais par connexion, 30 s pour s'authentifier, pas de redirections. Le pare-feu bloque 15 minutes une IP qui ouvre plus de 4 connexions SSH par minute. `off` désactive SSH (refusé si l'assistant tourne en SSH).
- **Retour arrière** : lancé en SSH, l'assistant demande d'ouvrir une nouvelle connexion puis de taper `OK` dans les 120 s ; sans réponse, `relay.conf`, le pare-feu et SSH reviennent à leur état précédent.
- **Annuaire public** : l'assistant propose (Oui par défaut sur une nouvelle installation, Non pour un relais mis à jour depuis 1.0.0) de publier le relais sur getparanoid.fr, avec un nom au choix. Sont publiés : nom, adresse et port publics, IP d'envoi, empreinte, version, heure du dernier signal. Le signal est signé par la clé TLS du relais ; le site vérifie la signature et que l'adresse publique correspond à l'IP d'envoi (détails dans `../site/README.md`). Répondre Non retire le relais de l'annuaire. `relayd` lui-même ne fait toujours aucune requête sortante.
- `apt remove` retire le relais de l'annuaire, l'arrête et garde clé et base ; `apt purge` les efface (l'empreinte est perdue).

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
