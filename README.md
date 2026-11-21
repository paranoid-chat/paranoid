# Paranoid

*La messagerie pour les vrais paranoïaques.*

Messagerie Android sans compte. Votre identité est une paire de clés créée sur le téléphone, qui ne le quitte jamais. Les contacts s'ajoutent par QR code ou en transmettant un code unique à un contact distant. Les messages transitent par un relais interchangeable qui ne voit que des blocs chiffrés. Le code est open source, chacun peut héberger son propre relais.

Site : [getparanoid.fr](https://getparanoid.fr)

## Fonctionnalités

- **Sans compte** : ni numéro, ni e-mail. Clés Ed25519 et X25519 générées sur le téléphone.
- **Appairage en un scan** : un QR à usage unique par contact.
- **Chiffrement Double Ratchet**, blocs de taille fixe (2048 octets).
- **Code de déverrouillage** Argon2id, coffre chiffré par le Keystore (StrongBox si disponible).
- **Code d'autodestruction** : saisi à la place du code normal, il ouvre une application vide, détruit les clés et alerte tous les contacts.
- **Relais au choix** : un relais public de l'annuaire ou votre propre relais, épinglé par l'empreinte de son certificat.
- **Historique local réglable**, de 24 heures à illimité.
- **Aucune notification, aucune sauvegarde, clavier sans apprentissage.**
- Français et anglais. Android 13 minimum.

## Limites connues

- Une analyse en profondeur des fichiers du téléphone révèle la présence de deux codes, sans les dévoiler.
- Sans réseau, l'alerte d'autodestruction ne part pas, mais la destruction a bien lieu.
- Un QR photographié et utilisé avant le vrai contact usurpe l'appairage. La comparaison des empreintes le révèle.
- Le relais voit les adresses IP et les heures.

## Installer l'application

Dernier APK : [paranoid.apk](https://github.com/paranoid-chat/paranoid/releases/latest/download/paranoid.apk)

Les notes de chaque release donnent le SHA-256 de l'APK et l'empreinte du certificat de signature, à comparer avec *Mes infos en direct* dans l'application. Une mise à jour conserve l'identité et les conversations.

## Héberger un relais

Paquet `.deb` (Debian 12+, amd64 et arm64), ISO amd64 ou image Raspberry Pi, publiés dans les releases `relay-vVERSION`. Voir [`relay/README.md`](relay/README.md) et la section [Héberger un relais](https://getparanoid.fr/technique.html#heberger-un-relais) du site.

```bash
apt install ./paranoid-relay_VERSION_amd64.deb
paranoid-relay config
```

## Structure du dépôt

| Dossier | Contenu |
|---|---|
| `app/` | Application Android (Kotlin, Compose) |
| `core/` | Crypto, appairage, Double Ratchet, client du relais (Kotlin pur, testé sur JVM) |
| `relay/` | Relais Go (`relayd`) et client de test `relaycli` |
| `images/` | Construction de l'ISO et de l'image Raspberry Pi |
| `site/` | Annuaire public des relais (PHP) |

Spécifications : [`PROTOCOL.md`](PROTOCOL.md) (client ↔ relais, QR) et [`MESSAGES.md`](MESSAGES.md) (appairage, Double Ratchet).

---

# Paranoid (English)

*The Chat app for the truly paranoids.*

Account-free Android messaging. Your identity is a key pair created on the phone, and it never leaves it. Contacts are added by QR code or by sending a one-time code to a remote contact. Messages go through an interchangeable relay that only sees encrypted blocks. The code is open source, and anyone can host their own relay.

Website: [getparanoid.fr](https://getparanoid.fr/en/)

## Features

- **No account**: no phone number, no e-mail. Ed25519 and X25519 keys generated on the phone.
- **One-scan pairing**: a one-time QR per contact.
- **Double Ratchet encryption**, fixed-size blocks (2048 bytes).
- **Unlock code** with Argon2id, vault encrypted by the Keystore (StrongBox when available).
- **Self-destruct code**: entered instead of the normal code, it opens an empty app, destroys the keys and alerts every contact.
- **Relay of your choice**: a public relay from the directory or your own, pinned by its certificate fingerprint.
- **Adjustable local history**, from 24 hours to unlimited.
- **No notifications, no backups, no keyboard learning.**
- French and English. Android 13 or later.

## Known limitations

- An in-depth analysis of the phone's files reveals that two codes exist, without disclosing them.
- Offline, the self-destruct alert is not sent, but the destruction still happens.
- A photographed QR used before the real contact hijacks the pairing. Comparing fingerprints exposes it.
- The relay sees IP addresses and times.

## Install the app

Latest APK: [paranoid.apk](https://github.com/paranoid-chat/paranoid/releases/latest/download/paranoid.apk)

Each release's notes give the APK's SHA-256 and the signing certificate fingerprint, to compare with *My live info* in the app. An update keeps the identity and conversations.

## Host a relay

`.deb` package (Debian 12+, amd64 and arm64), amd64 ISO or Raspberry Pi image, published in the `relay-vVERSION` releases. See [`relay/README.md`](relay/README.md) and the [Hosting a relay](https://getparanoid.fr/en/technical.html#hosting-a-relay) section of the website.

```bash
apt install ./paranoid-relay_VERSION_amd64.deb
paranoid-relay config
```

## Repository layout

| Folder | Contents |
|---|---|
| `app/` | Android app (Kotlin, Compose) |
| `core/` | Crypto, pairing, Double Ratchet, relay client (pure Kotlin, tested on the JVM) |
| `relay/` | Go relay (`relayd`) and `relaycli` test client |
| `images/` | ISO and Raspberry Pi image build |
| `site/` | Public relay directory (PHP) |

Specifications (in French): [`PROTOCOL.md`](PROTOCOL.md) (client ↔ relay, QR) and [`MESSAGES.md`](MESSAGES.md) (pairing, Double Ratchet).
