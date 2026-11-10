# Paranoid (Android) — v0.1

Messagerie sans compte : l'identité est une paire de clés créée sur le téléphone. Les contacts s'ajoutent par échange de QR en face à face, et les messages passent par un relais interchangeable qui ne voit que des blocs chiffrés (voir `PROTOCOL.md` et `MESSAGES.md` dans le dépôt du relais).

## Structure

| Module | Contenu |
|---|---|
| `core/` | Kotlin pur (JVM) : crypto, Double Ratchet (format v2, port exact de `relaycli`), invitations, client du relais avec épinglage du certificat, sérialisation de l'état, logique de messagerie (`Messenger`). Testé sur JVM et contre le relais et le client Go. |
| `app/` | Appli Android (Compose, Material 3) : écrans, QR (zxing, sans Google Play Services), stockage chiffré par le Keystore. |

## Ce que fait la v0.1

- Création de l'identité au premier lancement, après un test du relais (TLS et empreinte).
- Ajout de contact : nom local, puis affichage de mon QR, puis scan du sien. L'empreinte de sécurité est à comparer de vive voix.
- Envoi et réception de texte (1963 octets maximum), avec relève toutes les 5 s **quand l'appli est au premier plan**.
- Réinitialisation de session et suppression de contact (sa boîte est supprimée sur le relais).
- **Sécurité locale :**
  - État chiffré en AES-256-GCM avec une clé de données, elle-même chiffrée par une clé non exportable du Keystore (StrongBox si disponible).
  - Captures d'écran et aperçu dans les applis récentes bloqués (`FLAG_SECURE`).
  - Aucune sauvegarde : ni Google, ni transfert d'appareil.
  - Copie d'invitation marquée « sensible » dans le presse-papiers.

## Pas encore fait

- Code de déverrouillage et code de destruction (le stockage est prêt : `StateStore.wipe()` détruit la clé Keystore).
- Réception en arrière-plan et notifications.
- Clavier sans apprentissage (option « incognito » de l'IME).
- Plusieurs relais, et limitation de l'historique conservé.
- Relecture de sécurité par un tiers.

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

## En cas de problème

| Symptôme | Cause probable |
|---|---|
| « Relais injoignable » | Le téléphone n'est pas sur le LAN, ou le service `relay` est arrêté. |
| « empreinte du relais inattendue » | L'empreinte saisie ne correspond pas au certificat du relais. |
| Messages « rejetés » ou qui ne passent plus | Session désynchronisée : *Réinitialiser la session* des deux côtés (`relaycli reset tel` côté PC). |
| « Données illisibles » au démarrage | La clé Keystore a disparu (appli réinstallée sans ses données, par exemple). L'identité est perdue, c'est voulu. |
