# Paranoid (Android) — v0.3.1

Messagerie sans compte : l'identité est une paire de clés créée sur le téléphone. Les contacts s'ajoutent par échange de QR en face à face, et les messages passent par un relais interchangeable qui ne voit que des blocs chiffrés (voir `PROTOCOL.md` et `MESSAGES.md` dans le dépôt du relais).

## Structure

| Module | Contenu |
|---|---|
| `core/` | Kotlin pur (JVM) : crypto, Double Ratchet (format v2, port exact de `relaycli`), invitations, client du relais avec épinglage du certificat, sérialisation de l'état, logique de messagerie (`Messenger`). Testé sur JVM et contre le relais et le client Go. |
| `app/` | Appli Android (Compose, Material 3) : écrans, QR (zxing, sans Google Play Services), stockage chiffré par le Keystore. |

## Ce que fait la v0.3

- **Identité** : créée au premier lancement, après un test du relais (TLS et empreinte).
- **Contacts** : nom local, puis affichage de mon QR, puis scan du sien (ou invitation partagée en texte avec un PC sans caméra). L'empreinte de sécurité est à comparer de vive voix.
- **Messages** : texte chiffré par le Double Ratchet, 1963 octets maximum.
- **Code de déverrouillage** : Argon2id (64 Mio, 3 passes). Les essais sont temporisés à partir de 5 échecs (30 s, puis doublement). L'appli se verrouille avec le cadenas, ou après 60 s en arrière-plan.
- **Code de destruction (bouton rouge)**, saisi à la place du code normal :
  1. l'écran devient aussitôt celui d'une appli vierge ;
  2. la clé Keystore et le coffre sont détruits ;
  3. depuis la mémoire, l'alerte « X a utilisé le bouton rouge » part chez tous les contacts (5 s maximum) ;
  4. tout est effacé.
  Chez le destinataire, la conversation affiche l'alerte et n'accepte plus d'envoi.
- **Messages en attente** : sans notification, les messages attendent sur le relais (jusqu'à 7 jours, 100 au maximum par contact) et arrivent dès que l'appli est déverrouillée.
- **Notifications (facultatives)** : uniquement si un distributeur UnifiedPush (ntfy…) est déjà installé sur le téléphone. Sinon, l'appli ne demande rien. Le réveil envoyé par le relais est chiffré et vide :
  - appli déverrouillée : relève immédiate ;
  - sinon : notification générique « Nouveau message », sans expéditeur ni texte.
- **Sécurité locale** :
  - état chiffré par une clé de données, dans un coffre à deux codes, lui-même chiffré par une clé non exportable du Keystore (StrongBox si disponible) ;
  - `FLAG_SECURE` ;
  - aucune sauvegarde ;
  - clavier en mode mot de passe pour les codes.

## Pas encore fait

- Clavier sans apprentissage pour les messages.
- Plusieurs relais, et limitation de l'historique conservé.
- Masquer le nombre d'essais et l'existence d'un code de destruction face à une analyse du téléphone (voir « Limites » ci-dessous).
- Relecture de sécurité par un tiers.

## Limites connues (v0.3)

- **Code de destruction détectable** : le coffre contient deux emplacements. Quelqu'un qui analyse les fichiers voit qu'il existe deux codes, sans savoir lequel détruit.
- **Compteur d'essais contournable** : il est stocké dans un fichier, qu'un attaquant ayant les droits root pourrait restaurer.
- **Alerte sans réseau** : si le téléphone n'a pas de réseau au moment du bouton rouge, l'alerte ne part pas, mais la destruction a lieu quand même.

---

## Compiler et installer (pcport01, Debian)

### 1. Android Studio

1. Télécharge l'archive Linux sur developer.android.com/studio.
2. Installe-la :
   ```bash
   sudo tar -xzf ~/Téléchargements/android-studio-*-linux.tar.gz -C /opt
   /opt/android-studio/bin/studio.sh
   ```
3. Suis l'assistant en mode « Standard » : il installe le SDK Android.
4. Le premier lancement télécharge plusieurs Go.

### 2. Ouvrir le projet

1. Choisis *File → Open* et sélectionne le dossier `Paranoid`.
2. Laisse la synchronisation Gradle se terminer : elle télécharge Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21, Compose, BouncyCastle et zxing.
3. Si Android Studio propose de mettre à jour AGP ou Gradle, tu peux refuser. Le projet est prévu pour ces versions.

Ou en ligne de commande, une fois le SDK installé :

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :core:test :app:assembleDebug
```

Le test `:core:test` doit passer avant l'appli.

### 3. Préparer le téléphone (S23+)

1. Active les options de développement : *Paramètres → À propos du téléphone → Informations sur le logiciel*, puis touche 7 fois *Numéro de version*.
2. Active le débogage USB : *Paramètres → Options de développement → Débogage USB*.
3. Branche le téléphone et accepte l'empreinte du PC.
4. Dans Android Studio, sélectionne le téléphone et clique sur ▶ *Run*.
   - Ou en ligne de commande : `adb install -r app/build/outputs/apk/debug/app-debug.apk`

Le téléphone doit être sur **le Wi-Fi du LAN** : le pare-feu du relais n'accepte que `192.168.1.0/24`.

### 4. Premier lancement

Le relais et son empreinte sont préremplis (`https://192.168.1.252:8443` et `FB:2B:…:B0:EA`). Appuie sur *Créer mon identité*.

## Tester avec le client Go (`relaycli`)

Sur pcport01 :

```bash
cd ~/Documents/paranoid/test
C=~/Documents/paranoid/relay/relaycli
```

1. **Le PC montre son QR au téléphone** :
   ```bash
   $C -d alice invite tel 2>/dev/null | qrencode -t ansiutf8
   ```
   Sur le téléphone, appuie sur *+*, saisis le nom `alice` et appuie sur *Afficher mon QR*.
2. **Le PC enregistre l'invitation du téléphone.** Le PC n'a pas de caméra : sur le téléphone, *Copier l'invitation (tests)*, puis fais-la parvenir au PC par le moyen de ton choix (mail à toi-même par exemple, acceptable pour un test).
   ```bash
   $C -d alice add tel "<invitation du téléphone>"
   ```
3. **Le téléphone scanne le QR d'alice** : *Scanner son QR*, puis le QR du terminal. Compare les deux empreintes de sécurité.
4. **Discuter :**
   ```bash
   $C -d alice send tel "salut depuis le PC"     # visible sur le téléphone en moins de 5 s
   $C -d alice recv                              # après un envoi depuis le téléphone
   ```

Entre deux téléphones, l'échange se fait uniquement par QR, sans copie.

## Mettre à jour le relais (VM `caparanoid01`)

Le relais de la v0.3 ajoute les notifications. Il faut donc le mettre à jour.

1. Dans *Actions*, ouvre la dernière exécution **Relais** et télécharge l'artefact `relais-binaires`. Prends `relayd-linux-amd64`.
2. Copie-le (OpenSSH est inclus dans Windows) :
   ```powershell
   scp .\relayd-linux-amd64 matteo@192.168.1.252:/tmp/relayd
   ```
3. Installe-le sur la VM, en `su -` :
   ```bash
   install -o root -g root -m 0755 /tmp/relayd /opt/relay/relayd && rm /tmp/relayd
   systemctl restart relay
   journalctl -u relay -n 5 --no-pager
   ls -l /var/lib/relay/vapid.pem
   ```
   La clé VAPID est créée au premier démarrage, et la base est migrée automatiquement.
4. Le relais doit pouvoir joindre le service de notification en HTTPS sortant (443). Le pare-feu de la VM l'autorise déjà.
5. Avec un ntfy **sur le LAN**, il faut ajouter `--push-allow-private` à la ligne `ExecStart` de `relay.service`, puis `systemctl daemon-reload && systemctl restart relay`. Sinon le relais refuse de contacter une adresse privée (protection SSRF).

## Tester la v0.3

1. **Migration** : installe l'APK 0.3.0 par-dessus la 0.1.1. L'appli demande tes deux codes et conserve identité et conversations.
2. **Verrouillage** :
   - cadenas en haut de la liste des contacts, puis déverrouillage avec le code ;
   - 5 mauvais codes doivent déclencher la temporisation ;
   - en arrière-plan plus de 60 s, l'appli doit se reverrouiller.
3. **Notifications** (facultatif) :
   - installe **ntfy** sur le téléphone (F-Droid ou Play Store, serveur `ntfy.sh` par défaut) ;
   - ouvre Paranoid déverrouillé : accepte les notifications et choisis ntfy. La liste des contacts doit afficher « Notifications actives » ;
   - mets l'appli en arrière-plan ou verrouille-la, puis envoie un message depuis le PC (`& $C -d pc send tel "test"`) : la notification « Nouveau message » doit arriver en quelques secondes.
4. **Bouton rouge**, à faire en dernier : il faudra refaire l'appairage.
   - lance `& $C -d pc chat tel` sur le PC ;
   - sur le téléphone, verrouille puis saisis le **code de destruction**. L'appli doit afficher l'écran de création d'identité ;
   - le PC doit afficher l'alerte « tel a utilisé le bouton rouge », puis refuser l'envoi. Supprime ensuite le contact côté PC (`& $C -d pc remove tel`).
   - Pour tester l'autre sens : `& $C -d pc panic --oui`. Le téléphone affiche alors l'alerte dans la conversation.

`ntfy.sh` (public) voit l'adresse IP du relais et l'heure des réveils, jamais leur contenu. Pour l'auto-héberger plus tard : un service ntfy en HTTPS (avec `base-url` et `upstream-base-url`), dont l'adresse se règle dans l'appli ntfy du téléphone.

## Tester depuis le PC avec le téléphone (phase de développement)

La boucle de travail : un push sur `main`, la CI compile, puis tu récupères l'APK sur le PC, tu l'installes sur le téléphone et tu testes contre `relaycli`. Le PC joue le rôle du second téléphone.

### Outils (une fois, PowerShell)

```powershell
winget install GitHub.cli Google.PlatformTools
gh auth login                      # compte GitHub qui a accès au dépôt
```

Ferme puis rouvre PowerShell pour que `gh` et `adb` soient dans le PATH.

### Relier le téléphone à adb

**Par USB :** options de développement, puis *Débogage USB*. Branche le téléphone, accepte l'empreinte du PC, puis vérifie avec `adb devices`.

**En Wi-Fi (sans câble) :**
1. Dans les options de développement, ouvre *Débogage sans fil*, puis *Associer l'appareil avec un code*.
2. Sur le PC :
   ```powershell
   adb pair 192.168.1.X:PORT_ASSOCIATION    # saisir le code affiché
   adb connect 192.168.1.X:PORT_DEBOGAGE    # port affiché sur l'écran « Débogage sans fil »
   adb devices
   ```

### Installer la dernière version

Depuis le dossier du dépôt cloné, ou en copiant le dossier `tools` :

```powershell
.\tools\dernier-apk.ps1        # télécharge l'APK de la dernière compilation réussie, vérifie la somme, installe
.\tools\dernier-relaycli.ps1   # met à jour .\relaycli.exe
$C = ".\relaycli.exe"
```

`adb install -r` met l'appli à jour **sans perdre ses données**, puisque la signature et l'identité sont les mêmes. Pour repartir de zéro côté téléphone : `adb shell pm clear app.paranoid`, qui efface aussi la clé Keystore de l'appli.

### Suivre l'appli pendant un test

```powershell
adb logcat --pid=$(adb shell pidof -s app.paranoid)
```

L'appli n'écrit jamais de secret dans les journaux. Les plantages, eux, y apparaissent (`FATAL EXCEPTION`). Côté relais (sur la VM) : `journalctl -u relay -f`.

### Scénarios de test

| # | Scénario | Attendu |
|---|---|---|
| 1 | Appairage : téléphone *+* → *Afficher mon QR* → *Partager* ; PC `Get-Clipboard \| & $C -d pc add tel -` ; PC `& $C -d pc invite --qr tel` ; téléphone *Scanner son QR* | Les empreintes concordent des deux côtés |
| 2 | `& $C -d pc chat tel`, puis messages dans les deux sens | Arrivée en moins de 5 s |
| 3 | Téléphone verrouillé, puis le PC envoie 3 messages, puis déverrouillage | Les 3 messages arrivent, dans l'ordre |
| 4 | Le téléphone envoie avec le relais arrêté (`systemctl stop relay`) | Message marqué « échec », l'appli reste utilisable |
| 5 | 5 mauvais codes | Temporisation de 30 s |
| 6 | Appli en arrière-plan plus de 60 s | Reverrouillée au retour |
| 7 | Mise à jour (`dernier-apk.ps1`) | Identité et conversations conservées |
| 8 | Code de destruction sur le téléphone, pendant le `chat` PC | Téléphone : écran d'identité vierge. PC : alerte, puis envoi refusé |
| 9 | `& $C -d pc panic --oui` | Téléphone : alerte dans la conversation, saisie remplacée par « Supprimer » |

Après les scénarios 8 et 9, refais l'appairage. Côté PC : `& $C -d pc remove tel` (ou supprime le dossier `pc`) ; côté téléphone : supprime le contact.

En cas d'échec de déchiffrement, garde la ligne « rejeté » de `relaycli` (elle contient le type de chaîne et les numéros) et la sortie de `& $C -d pc debug tel`.

## En cas de problème

| Symptôme | Cause probable |
|---|---|
| « Relais injoignable » | Le téléphone n'est pas sur le LAN, ou le service `relay` est arrêté. |
| « empreinte du relais inattendue » | L'empreinte saisie ne correspond pas au certificat du relais. |
| Messages « rejetés » ou qui ne passent plus | Session désynchronisée : *Réinitialiser la session* des deux côtés (`relaycli reset tel` côté PC). |
| « Données illisibles » au démarrage | La clé Keystore a disparu (appli réinstallée sans ses données, par exemple). L'identité est perdue, c'est voulu. |
| Aucune mention « Notifications actives » | Pas de distributeur UnifiedPush sur le téléphone (c'est normal), ou relais pas encore en v0.3. |
| Pas de notification alors que « Notifications actives » | Le relais ne joint pas le service de notification : regarde `journalctl -u relay`, et vérifie l'option `--push-allow-private` si ntfy est sur le LAN. Vérifie aussi que l'optimisation de batterie de ntfy est désactivée. |
