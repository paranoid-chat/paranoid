# Paranoid — v0.4.0

*The Chat app for the truly paranoids.*

Messagerie sans compte : l'identité est une paire de clés créée sur le téléphone, qui ne le quitte jamais. Les contacts s'ajoutent par échange de QR en face à face. Les messages passent par un relais interchangeable qui ne voit que des blocs chiffrés de taille fixe (voir `PROTOCOL.md` et `MESSAGES.md`).

## Structure du dépôt

| Dossier | Contenu |
|---|---|
| `app/` | Appli Android (Compose, Material 3) : écrans, QR (zxing, sans Google Play Services), stockage chiffré par le Keystore. |
| `core/` | Kotlin pur (JVM) : crypto, Double Ratchet, coffre à deux codes, invitations, client du relais avec épinglage du certificat, sérialisation de l'état. Testé sur JVM et contre le relais Go. |
| `relay/` | Relais Go (SQLite) et `relaycli`, le client de test en ligne de commande (Linux et Windows). |

## Ce que fait la v0.4

- **Identité** : créée au premier lancement, après un test du relais (TLS et empreinte).
- **Contacts** : nom local, puis affichage de mon QR, puis scan du sien. Avec un PC sans caméra, l'invitation peut aussi être partagée en texte. L'empreinte de sécurité est à comparer de vive voix.
- **Messages** : texte chiffré par le Double Ratchet, 1963 octets maximum.
- **Code de déverrouillage** : Argon2id (64 Mio, 3 passes). Les essais sont temporisés à partir de 5 échecs (30 s, puis doublement). L'appli se verrouille avec le cadenas, ou après 60 s en arrière-plan.
- **Code de destruction (bouton rouge)**, saisi à la place du code normal :
  1. l'écran devient aussitôt celui d'une appli vierge ;
  2. la clé Keystore et le coffre sont détruits ;
  3. depuis la mémoire, l'alerte « X a utilisé le bouton rouge » part chez tous les contacts (5 s maximum) ;
  4. tout est effacé.

  Chez le destinataire, la conversation affiche l'alerte et n'accepte plus d'envoi.
- **Pas de notifications**, par choix de sécurité. Les messages attendent sur le relais (jusqu'à 7 jours, 100 au maximum par contact) et arrivent quand l'appli est ouverte et déverrouillée.
- **Sécurité locale** :
  - état chiffré par une clé de données, dans un coffre à deux codes, lui-même chiffré par une clé non exportable du Keystore (StrongBox si disponible) ;
  - `FLAG_SECURE` (pas de capture d'écran, pas d'aperçu dans les applis récentes) ;
  - aucune sauvegarde.

## Pas encore fait

- Clavier sans apprentissage pour les messages.
- Plusieurs relais, et limitation de l'historique conservé.
- Appairage en un seul scan.
- Relecture de sécurité par un tiers.

## Limites connues

- **Code de destruction détectable** : le coffre contient deux emplacements. Quelqu'un qui analyse les fichiers voit qu'il existe deux codes, sans savoir lequel détruit.
- **Compteur d'essais contournable** : il est stocké dans un fichier, qu'un attaquant ayant les droits root pourrait restaurer.
- **Alerte sans réseau** : si le téléphone n'a pas de réseau au moment du bouton rouge, l'alerte ne part pas, mais la destruction a lieu quand même.

---

## Compilation (GitHub Actions)

Chaque push sur `main` déclenche la CI :

| Exécution | Artefact | Contenu |
|---|---|---|
| **Android** | `paranoid-apk` | APK signé avec la clé du dépôt (secrets `PARANOID_*`) + `SHA256SUMS` |
| **Relais** (si `relay/` change) | `relais-binaires` | `relayd-linux-amd64`, `relayd-linux-arm64`, `relaycli-linux-*`, `relaycli-windows-amd64.exe` + `SHA256SUMS` |

Les artefacts se téléchargent depuis l'onglet *Actions* : ouvre l'exécution, puis la section *Artifacts* en bas de page. Ils sont conservés 14 jours.

## Installer l'APK sur le téléphone

1. Télécharge `paranoid-apk` depuis la dernière exécution **Android**, puis décompresse-le.
2. Copie `paranoid-<version>-<commit>.apk` sur le téléphone et ouvre-le avec *Mes fichiers*. Autorise « Installer des applis inconnues » pour *Mes fichiers*.
3. Sur un Samsung, désactive la **Protection automatique** (*Paramètres → Sécurité et confidentialité*) le temps de l'installation.
4. Une mise à jour s'installe par-dessus la version précédente et **conserve identité et conversations**, grâce à la même clé de signature.

Le téléphone doit être sur le **Wi-Fi du LAN** : le pare-feu du relais n'accepte que `192.168.1.0/24`.

---

## Tester depuis le PC (Windows) avec le téléphone

Le PC joue le rôle du second téléphone, grâce à `relaycli`.

### Une seule fois : préparer le PC

1. Télécharge `relais-binaires` depuis la dernière exécution **Relais**, puis copie `relaycli-windows-amd64.exe` dans un dossier, par exemple `C:\Users\Admin\Documents\Paranoid`.
2. Dans PowerShell :
   ```powershell
   cd C:\Users\Admin\Documents\Paranoid
   Unblock-File .\relaycli-windows-amd64.exe
   $C = ".\relaycli-windows-amd64.exe"
   & $C -d pc init --relay https://192.168.1.252:8443 --fp FB:2B:01:81:C4:A7:E4:EA:3C:C9:DE:31:EA:A4:96:5A:D4:7E:C0:D6:F6:B7:4B:91:4F:DD:FB:FC:89:C6:B0:EA
   ```
   La commande doit répondre « Relais joignable, empreinte vérifiée ». Elle crée l'identité du PC dans le dossier `pc`.

À chaque nouvelle session PowerShell, refais seulement `cd …` et `$C = ".\relaycli-windows-amd64.exe"`.

### Appairage PC ↔ téléphone

S'il existait déjà un appairage, supprime-le d'abord des deux côtés :
- sur le PC : `& $C -d pc remove tel` ;
- sur le téléphone : ouvre la conversation, menu ⋮, puis *Supprimer le contact*.

Fais ensuite l'appairage **d'une traite, sans revenir en arrière sur le téléphone** :

1. **Téléphone :** *+*, puis le nom `pc`, puis *Afficher mon QR*, puis *Partager*. Envoie le texte vers le PC (Quick Share, Lien avec Windows, ou un mail à toi-même).
2. **PC :** copie ce texte dans le presse-papiers, puis :
   ```powershell
   Get-Clipboard | & $C -d pc add tel -
   ```
3. **PC :**
   ```powershell
   & $C -d pc invite --qr tel
   ```
   Si le QR s'affiche mal dans le terminal, utilise `& $C -d pc invite --png qr.png tel`, puis `start qr.png`.
4. **Téléphone,** sur ce même écran : *Scanner son QR*, puis vise le QR du PC.
5. **Vérifier les empreintes :**
   - l'empreinte de `tel` affichée par le PC à l'étape 2 doit être « Mon empreinte » sur le téléphone ;
   - l'empreinte de `pc` affichée par le téléphone doit être celle donnée par `& $C -d pc whoami`.

### Discuter

```powershell
& $C -d pc chat tel
```

Tape un message puis Entrée. Les messages du téléphone s'affichent au fil de l'eau (relève toutes les 3 s), et `/quit` permet de quitter. Côté téléphone, l'appli relève toutes les 5 s quand elle est ouverte et déverrouillée.

N'ouvre qu'**une seule** fenêtre `relaycli` à la fois sur le dossier `pc`. Deux processus simultanés désynchronisent la session.

### Scénarios de test

| # | Scénario | Attendu |
|---|---|---|
| 1 | Messages dans les deux sens pendant le `chat` | Arrivée en quelques secondes |
| 2 | Téléphone verrouillé, puis le PC envoie 3 messages, puis déverrouillage | Les 3 messages arrivent, dans l'ordre |
| 3 | Cadenas, puis déverrouillage avec le code | Retour à la liste des contacts |
| 4 | 5 mauvais codes | Temporisation de 30 s |
| 5 | Appli en arrière-plan plus de 60 s | Reverrouillée au retour |
| 6 | Installer une nouvelle version par-dessus | Identité et conversations conservées |
| 7 | Pendant le `chat`, saisir le **code de destruction** sur le téléphone | Téléphone : écran d'identité vierge. PC : alerte « tel a utilisé le bouton rouge », puis envoi refusé |
| 8 | `& $C -d pc panic --oui` (bouton rouge côté PC) | Téléphone : alerte dans la conversation, saisie remplacée par « Supprimer la conversation » |

Après le scénario 7, supprime le contact côté PC (`& $C -d pc remove tel`), recrée une identité sur le téléphone, puis refais l'appairage. Après le scénario 8, le dossier `pc` est effacé : refais l'`init`, supprime le contact sur le téléphone, puis refais l'appairage.

### Diagnostic

- `& $C -d pc debug tel` : état de la session (rôle, compteurs), sans rien de secret.
- Une ligne « message n° X rejeté » de `relaycli` indique le type de chaîne et les numéros du bloc.
- Si les messages ne passent plus, deux solutions :
  - réinitialiser la session des deux côtés : menu ⋮, puis *Réinitialiser la session* sur le téléphone, et `& $C -d pc reset tel` sur le PC ;
  - ou refaire l'appairage.

---

## Mettre à jour le relais (VM `caparanoid01`)

1. Télécharge `relais-binaires` depuis la dernière exécution **Relais**.
2. Copie `relayd-linux-amd64` sur la VM (OpenSSH est inclus dans Windows) :
   ```powershell
   scp .\relayd-linux-amd64 matteo@192.168.1.252:/tmp/
   ```
3. Installe-le sur la VM, en `su -` :
   ```bash
   install -o root -g root -m 0755 /tmp/relayd-linux-amd64 /opt/relay/relayd && rm /tmp/relayd-linux-amd64
   systemctl restart relay
   journalctl -u relay -n 5 --no-pager
   ```
   Le journal doit afficher « relais à l'écoute sur :8443 » avec l'empreinte `FB:2B:…:B0:EA`.

## En cas de problème

| Symptôme | Cause probable |
|---|---|
| « Relais injoignable » | Le téléphone n'est pas sur le Wi-Fi du LAN, ou le service `relay` est arrêté (`systemctl status relay`). |
| « empreinte du relais inattendue » | L'empreinte saisie ne correspond pas au certificat du relais. |
| Message « rejeté » côté PC, ou message jamais reçu | Appairage réparti sur deux contacts côté téléphone, ou deux `relaycli` ouverts en même temps. Refais l'appairage d'une traite. |
| « Données illisibles » au démarrage | La clé Keystore a disparu (appli désinstallée, par exemple). L'identité est perdue, c'est voulu. |
| L'APK refuse de s'installer par-dessus | Signature différente (APK d'une compilation sans les secrets). Il faut désinstaller, ce qui perd l'identité. |
