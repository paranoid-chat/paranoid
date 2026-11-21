# Paranoid

*The Chat app for the truly paranoids.*

Messagerie Android sans compte. Votre identité est une paire de clés créée sur le téléphone, qui ne le quitte jamais. Les contacts s'ajoutent en scannant un QR en face à face. Les messages transitent par un relais interchangeable qui ne voit que des blocs chiffrés.

Site : [getparanoid.fr](https://getparanoid.fr)

## Fonctionnalités

- **Sans compte** : ni numéro, ni e-mail. Clés Ed25519 et X25519 générées sur le téléphone.
- **Appairage en un scan** : un QR à usage unique par contact, puis comparaison des empreintes de vive voix.
- **Chiffrement Double Ratchet** (spécification Signal), blocs de taille fixe (2048 octets).
- **Code de déverrouillage** Argon2id, coffre chiffré par le Keystore (StrongBox si disponible).
- **Code d'autodestruction** : saisi à la place du code normal, il ouvre une appli vide, détruit les clés et alerte tous les contacts.
- **Relais au choix** : un relais public de l'annuaire ou votre propre relais, épinglé par l'empreinte de son certificat.
- **Historique local réglable**, de 24 heures à illimité.
- **Aucune notification, aucune sauvegarde, clavier sans apprentissage**, `FLAG_SECURE`.
- Français et anglais. Android 13 minimum.

## Limites connues

- Une analyse des fichiers révèle qu'il existe deux codes, sans dire lequel détruit.
- Un attaquant root peut restaurer le compteur d'essais.
- Sans réseau, l'alerte d'autodestruction ne part pas, mais la destruction a bien lieu.
- Un QR photographié et utilisé avant le vrai contact usurpe l'appairage. La comparaison des empreintes le révèle.
- Le relais voit les adresses IP et les heures.
- Aucune relecture de sécurité par un tiers pour l'instant.

## Installer l'appli

Dernier APK : [paranoid.apk](https://github.com/paranoid-chat/paranoid/releases/latest/download/paranoid.apk)

Les notes de chaque release donnent le SHA-256 de l'APK et l'empreinte du certificat de signature, à comparer avec *Mes infos en direct* dans l'appli. Une mise à jour conserve l'identité et les conversations.

## Héberger un relais

Paquet `.deb` (Debian 12+, amd64 et arm64), ISO amd64 ou image Raspberry Pi, publiés dans les releases `relay-vVERSION`. Voir [`relay/README.md`](relay/README.md) et la section [Héberger un relais](https://getparanoid.fr/technique.html#heberger-un-relais) du site.

```bash
apt install ./paranoid-relay_VERSION_amd64.deb
paranoid-relay config
```

## Structure du dépôt

| Dossier | Contenu |
|---|---|
| `app/` | Appli Android (Kotlin, Compose) |
| `core/` | Crypto, appairage, Double Ratchet, client du relais (Kotlin pur, testé sur JVM) |
| `relay/` | Relais Go (`relayd`) et client de test `relaycli` |
| `images/` | Construction de l'ISO et de l'image Raspberry Pi |
| `site/` | Annuaire public des relais (PHP) |

Spécifications : [`PROTOCOL.md`](PROTOCOL.md) (client ↔ relais, QR) et [`MESSAGES.md`](MESSAGES.md) (appairage, Double Ratchet).

## Compilation

GitHub Actions compile tout à chaque push sur `main` : APK signé, binaires et paquets du relais, images. Chaque nouvelle version publie une release.

En local, le relais se teste avec `go test ./...` dans `relay/` (Go 1.26+).
