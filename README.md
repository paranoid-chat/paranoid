# Paranoid — v1.3.3

*The Chat app for the truly paranoids.*

Messagerie Android sans compte : l'identité est une paire de clés créée sur le téléphone, qui ne le quitte jamais. Les contacts s'ajoutent par un scan de QR en face à face. Les messages passent par un relais interchangeable qui ne voit que des blocs chiffrés de 2048 octets.

Documents de référence : `PROTOCOL.md` (client ↔ relais, contenu du QR), `MESSAGES.md` (appairage, Double Ratchet, types de message), `relay/README.md` (compiler et tester le relais).

## Structure du dépôt

| Dossier | Contenu |
|---|---|
| `app/` | Appli Android (Compose, Material 3) : écrans, QR (zxing, sans Google Play Services), stockage chiffré par le Keystore, menu *Comprendre Paranoid*. |
| `core/` | Kotlin pur (JVM) : crypto, appairage, Double Ratchet, coffre à deux codes, invitations, client du relais avec épinglage du certificat, sérialisation de l'état. Testé sur JVM et contre le Go. |
| `site/` | Annuaire public des relais pour getparanoid.fr (PHP, à intégrer au site). |
| `relay/` | Relais Go (`relayd`, SQLite) et `relaycli`, le client de test en ligne de commande (Linux et Windows). |

## Fonctionnalités

- **Identité** : créée au premier lancement, après un test du relais (TLS et empreinte du certificat).
- **Appairage en un seul scan** : l'un affiche son QR, l'autre le scanne en face à face. Le téléphone qui scanne envoie ses clés par le relais, chiffrées par un secret à usage unique contenu dans le QR et signées par sa clé d'identité ; l'appairage se termine tout seul chez celui qui a affiché le QR. Les empreintes sont ensuite à comparer de vive voix. Avec un PC sans caméra, l'invitation passe en texte.
- **Messages** : texte chiffré par le Double Ratchet, 1963 octets maximum.
- **Code de déverrouillage** : Argon2id (64 Mio, 3 passes). Après 5 échecs, attente de 30 s, doublée à chaque échec (1 h maximum). Verrouillage avec le cadenas, ou après 60 s en arrière-plan.
- **Code de destruction**, saisi à la place du code normal. L'appli fait **comme si le code était bon** et s'ouvre sur une liste de contacts vide. En arrière-plan :
  1. la clé Keystore et le coffre sont détruits ;
  2. une nouvelle identité est créée sur le même relais ; le code qui vient d'être saisi devient son code de déverrouillage, son code de destruction est aléatoire (à redéfinir dans *Modification des codes*). Si l'ancien état est illisible, le relais est le premier relais en ligne de l'annuaire public ; si l'annuaire est vide ou injoignable, l'identité n'a aucun relais : rien ne l'indique sur l'écran d'accueil, *Ajouter un contact* demande de choisir d'abord un relais (*Changer de relais*) ;
  3. depuis la mémoire, l'alerte « X a utilisé son code de destruction » part chez tous les contacts (5 s maximum) ;
  4. l'ancien état est effacé de la mémoire.

  Chez le destinataire, la conversation affiche l'alerte et n'accepte plus d'envoi.
- **Modification des codes** (menu ⋮) : le code actuel est exigé ; le code de destruction y a le même effet qu'au déverrouillage.
- **Création de l'identité** : sous « Sélectionner un relais », la liste des relais publics est chargée d'office (bouton *Actualiser la liste*), puis la partie relais privé (empreinte facultative). Aucun relais n'est prérempli.
- **Changer de relais** (menu ⋮) : deux façons de choisir le nouveau relais.
  - **Relais publics** : *Afficher les relais publics* charge l'annuaire de getparanoid.fr (`https://getparanoid.fr/relais.php?format=json` : la page Relais du site, en JSON, HTTPS classique, à la demande seulement : getparanoid.fr voit alors l'IP du téléphone). Un appui sur un relais reprend son adresse et son empreinte, sans rien taper. Le relais actuel est signalé et non sélectionnable.
  - **Relais privé** : adresse saisie à la main ; l'empreinte SHA-256 est facultative. Sans elle, *Lire l'empreinte présentée par le relais* fait une poignée de main TLS (rien n'est envoyé) et remplit le champ ; à comparer si possible avec `paranoid-relay info` chez l'opérateur, c'est cette empreinte qui est épinglée.

  Le changement est volontairement sans migration, **tout est effacé**. Le code actuel est exigé (le code de destruction y a le même effet qu'au déverrouillage), puis le nouveau relais est testé : en cas d'échec, rien n'est touché. Chaque contact reçoit « X a changé de relais » et sa conversation se ferme. Ensuite : nouvelle identité, nouveaux codes, nouvel appairage avec chacun.
- **Paramètres** (menu ⋮) :
  - **Conservation de l'historique** : durée de conservation sur un curseur (pour toujours, 1 an, 6 mois, 3 mois, 1 mois, 1 semaine, 3 jours, 24 heures). Les messages plus anciens sont effacés de ce téléphone au déverrouillage, à chaque relève et dès que le réglage change (confirmation avant d'effacer). Rien n'est effacé chez les contacts. Les alertes « code de destruction » et « changement de relais » sont toujours gardées.
  - **Langue** : français ou anglais. Le choix passe par le réglage Android par appli : il apparaît aussi dans *Paramètres Android → Applis → Paranoid → Langue*. Sans choix, l'appli suit la langue du téléphone (français si le téléphone est en français, anglais sinon). Un bouton *English / Français* est aussi proposé sur l'écran de création d'identité.
  - Tous les textes de l'appli sont traduits, y compris les fiches *Comprendre Paranoid* et les messages d'erreur. `relayd` et `relaycli` restent en français.
- **Comprendre Paranoid** (menu ⋮) : fiches hors ligne avec schémas, à deux niveaux (simple, puis technique), sur l'identité, l'appairage, le relais, le chiffrement, les codes, le stockage, les notifications, le changement de relais, les limites, la vérification et un glossaire.
- **Mes infos en direct** (menu ⋮) : affiche l'empreinte de sécurité, le relais et l'empreinte de son certificat, la protection de la clé (StrongBox, TEE ou logiciel) et l'empreinte du certificat de signature de l'APK.
- **Donation** (menu ⋮, en dernier) : même contenu que la section Donation de getparanoid.fr/contact.php : adresse Monero, *Copier l'adresse* et *Partager* (menu de partage Android : e-mail, messagerie…).
- **Clavier sans apprentissage** : chaque champ de saisie demande au clavier de ne rien apprendre ni mémoriser (`IME_FLAG_NO_PERSONALIZED_LEARNING`, mode incognito sur Gboard).
- **Relais plein** : le relais refuse les nouvelles boîtes au-delà de 90 % de sa taille maximale, et les dépôts à 100 % ou quand le disque est presque plein ; l'appli affiche « le relais est plein ».
- **Pas de notifications**, par choix de sécurité. Les messages attendent sur le relais (7 jours, 100 au maximum par contact) et arrivent quand l'appli est ouverte et déverrouillée.
- **Sécurité locale** : état chiffré par une clé de données, dans un coffre à deux codes, lui-même chiffré par une clé non exportable du Keystore (StrongBox si disponible) ; `FLAG_SECURE` ; aucune sauvegarde.

Android 13 minimum.

## Limites connues

- **Code de destruction détectable** : le coffre contient deux emplacements. Une analyse des fichiers montre qu'il existe deux codes, sans dire lequel détruit.
- **Compteur d'essais contournable** : il est stocké dans un fichier, qu'un attaquant root pourrait restaurer.
- **Alerte sans réseau** : sans réseau au moment du code de destruction, l'alerte ne part pas, mais la destruction a lieu.
- **QR photographié** : quelqu'un qui photographie un QR et l'utilise avant le vrai contact est appairé à sa place. La comparaison des empreintes le révèle.
- **Clavier** : la demande « sans apprentissage » peut être ignorée par un clavier tiers.
- **Métadonnées** : le relais voit les adresses IP et les heures.
- **Historique** : l'effacer ne l'efface que sur son propre téléphone. L'âge d'un message reçu est l'heure annoncée par l'expéditeur (jamais dans le futur).
- **Pas encore de relecture de sécurité par un tiers.**

## Prochaines étapes

Image ISO Debian amd64 du relais, qui embarque le paquet `paranoid-relay` : installation automatique après confirmation de l'effacement du disque, assistant au premier démarrage, `unattended-upgrades`, publication dans un dépôt public séparé.

---

## Compilation (GitHub Actions)

Chaque push sur `main` déclenche la CI :

| Exécution | Artefact | Contenu |
|---|---|---|
| **Android** (hors `relay/` et `*.md`) | `paranoid-apk` | APK signé avec la clé du dépôt (secrets `PARANOID_*`) + `SHA256SUMS` |
| **Relais** (si `relay/` change) | `relais-binaires` | `relayd-linux-amd64`, `relayd-linux-arm64`, `relaycli-linux-*`, `relaycli-windows-amd64.exe`, `paranoid-relay_*_amd64.deb`, `paranoid-relay_*_arm64.deb` + `SHA256SUMS` |

Les artefacts se téléchargent depuis l'onglet *Actions* : ouvre l'exécution, puis la section *Artifacts* en bas de page. Ils sont conservés 14 jours.

## Installer l'APK sur le téléphone

### Depuis le téléphone (release)

Chaque nouvelle version (`versionName` changé sur `main`) est publiée en release GitHub, avec l'APK sous un nom fixe. Le dernier APK est toujours à cette adresse :

```
https://github.com/matteovaccari/paranoid/releases/latest/download/paranoid.apk
```

Le dépôt est privé : le navigateur du téléphone doit être connecté à un compte GitHub qui a accès au dépôt. Les notes de la release donnent le SHA-256 de l'APK et celui du certificat de signature, à comparer avec *Mes infos en direct*.

### Depuis la CI (artefact)

1. Télécharge `paranoid-apk` depuis la dernière exécution **Android**, puis décompresse-le. Vérifie l'empreinte : `Get-FileHash .\paranoid-*.apk -Algorithm SHA256` doit donner la valeur de `SHA256SUMS`.
2. Copie `paranoid-<version>-<commit>.apk` sur le téléphone et ouvre-le avec *Mes fichiers*. Autorise « Installer des applis inconnues » pour *Mes fichiers*.
3. Sur un Samsung, désactive la **Protection automatique** (*Paramètres → Sécurité et confidentialité*) le temps de l'installation.
4. Une mise à jour s'installe par-dessus et **conserve identité et conversations**, grâce à la même clé de signature.

Le relais est joignable sur le LAN (`https://192.168.1.252:8443`) et depuis Internet (`https://<IP_PUBLIQUE>`, port 443 de la box redirigé vers 8443). L'adresse choisie à la création de l'identité est celle qui figure dans les QR : pour être joignable en 4G, utilise l'adresse publique.

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
   & $C -d pc init --relay https://<IP_PUBLIQUE> --fp FB:2B:01:81:C4:A7:E4:EA:3C:C9:DE:31:EA:A4:96:5A:D4:7E:C0:D6:F6:B7:4B:91:4F:DD:FB:FC:89:C6:B0:EA
   ```
   La commande doit répondre « Relais joignable, empreinte vérifiée ». Elle crée l'identité du PC dans le dossier `pc`.

À chaque nouvelle session PowerShell, refais seulement `cd …` et `$C = ".\relaycli-windows-amd64.exe"`.

### Appairage PC ↔ téléphone

S'il existait déjà un contact, supprime-le d'abord des deux côtés :
- sur le PC : `& $C -d pc remove tel` ;
- sur le téléphone : ouvre la conversation, menu ⋮, puis *Supprimer le contact*.

**Le téléphone affiche, le PC « scanne »** (le plus simple) :

1. **Téléphone :** *+*, nom `pc`, *Afficher mon QR*, puis *Partager*. Envoie le texte vers le PC (Quick Share, Lien avec Windows, ou un mail à toi-même).
2. **PC :** copie ce texte dans le presse-papiers, puis :
   ```powershell
   Get-Clipboard | & $C -d pc add tel -
   ```
3. **Téléphone :** l'écran passe tout seul à « Appairage terminé » en quelques secondes.
4. **Vérifier les empreintes** : celle de `tel` affichée par le PC = « Mon empreinte » sur le téléphone ; celle de `pc` affichée par le téléphone = `& $C -d pc whoami`.

**Le PC affiche, le téléphone scanne** :

1. **PC :** `& $C -d pc invite --qr tel` (si le QR s'affiche mal : `& $C -d pc invite --png qr.png tel`, puis `start qr.png`).
2. **Téléphone :** *+*, nom `pc`, *Scanner son QR*, vise le QR du PC. La discussion s'ouvre.
3. **PC :** `& $C -d pc recv` affiche « Appairage avec tel terminé ».
4. Vérifier les empreintes comme ci-dessus.

### Discuter

```powershell
& $C -d pc chat tel
```

Tape un message puis Entrée. Les messages du téléphone s'affichent au fil de l'eau (relève toutes les 3 s), `/quit` pour quitter. Côté téléphone, l'appli relève toutes les 5 s quand elle est ouverte et déverrouillée. N'ouvre qu'une seule fenêtre `relaycli` à la fois sur le dossier `pc`.

### Scénarios de test

| # | Scénario | Attendu |
|---|---|---|
| 1 | Appairage, le téléphone affiche son QR | Téléphone : « Appairage terminé » sans autre action ; empreintes identiques des deux côtés |
| 2 | Appairage, le PC affiche son QR | Téléphone : discussion ouverte après le scan ; PC (`recv`) : « Appairage terminé » |
| 3 | Réutiliser une invitation déjà servie (`add` une seconde fois, sous un autre nom) | Refus |
| 4 | Messages dans les deux sens pendant `chat` | Arrivée en quelques secondes |
| 5 | Téléphone verrouillé, le PC envoie 3 messages, puis déverrouillage | Les 3 messages arrivent, dans l'ordre |
| 6 | Cadenas, puis déverrouillage | Retour à la liste des contacts |
| 7 | 5 mauvais codes | Attente de 30 s |
| 8 | Appli en arrière-plan plus de 60 s | Reverrouillée au retour |
| 9 | Installer une nouvelle version par-dessus | Identité et conversations conservées |
| 10 | Gboard : taper un mot inhabituel dans un message | Gboard affiche le mode incognito ; le mot n'est pas proposé ensuite dans d'autres applis |
| 11 | Pendant le `chat`, verrouiller le téléphone puis saisir le **code de destruction** | Téléphone : liste de contacts vide, empreinte différente. PC : alerte « tel a utilisé son code de destruction », puis envoi refusé |
| 12 | Rouvrir avec le même code de destruction | Même appli vide ; puis *Modification des codes* avec ce code comme code actuel |
| 13 | `& $C -d pc panic --oui` (code de destruction côté PC) | Téléphone : alerte dans la conversation, saisie remplacée par « Supprimer la conversation » |
| 14 | *Changer de relais* avec une mauvaise empreinte | « empreinte du relais inattendue », rien n'est effacé |
| 15 | *Changer de relais* valide (par exemple du LAN vers l'adresse publique) | PC : « tel a changé de relais », envoi refusé. Téléphone : choix des codes, puis liste vide |
| 16 | *Changer de relais* avec le code de destruction | Bouton rouge |
| 17 | *Mes infos en direct* | Empreinte et relais identiques à l'écran principal ; signature identique à `apksigner verify --print-certs` |
| 18 | *Paramètres* : curseur sur « 24 heures » avec des messages plus anciens | Confirmation « Effacer N message(s) ? », puis messages effacés ; les cartes d'alerte restent |
| 19 | Curseur sur « 24 heures », puis appli fermée plus de 24 h | Au déverrouillage, les messages de plus de 24 h ont disparu |
| 20 | Relais lancé avec `--max-db 12K` (test seulement), puis plusieurs envois | « le relais est plein » après quelques messages (mesure toutes les 10 s) |
| 21 | *Paramètres → Langue → English* | L'appli se rouvre en anglais, sans reverrouillage ; le choix reste après redémarrage et apparaît dans les réglages Android de l'appli |
| 22 | *Changer de relais → Afficher les relais publics* | Liste de l'annuaire (en ligne d'abord) ; le relais actuel marqué « Relais actuel » ; un appui affiche l'empreinte et « Nouveau relais : … » |
| 23 | *Changer de relais*, relais privé sans empreinte → *Lire l'empreinte présentée par le relais* | Champ rempli avec l'empreinte du relais, identique à `paranoid-relay info` |
| 24 | *Donation* → *Copier l'adresse*, coller ailleurs | Adresse identique à celle du site |

Après les scénarios 11, 15 et 16 : `& $C -d pc remove tel`, supprimer le contact sur le téléphone, puis refaire l'appairage. Après le 13, le dossier `pc` est effacé : refais aussi l'`init`.

### Diagnostic

- `& $C -d pc contacts` : état de chaque contact (en attente de scan, empreinte, conversation fermée).
- `& $C -d pc debug tel` : état de la session (rôle, compteurs), sans rien de secret.
- Une ligne « message n° X rejeté » de `relaycli` indique le type de chaîne et les numéros du bloc.
- Si les messages ne passent plus : *Réinitialiser la session* sur le téléphone (menu ⋮) et `& $C -d pc reset tel`, ou refaire l'appairage.

---

## Mettre à jour le relais (VM du relais)

Le relais est installé par le paquet `paranoid-relay` (détails dans `relay/README.md`).

1. Télécharge `relais-binaires` depuis la dernière exécution **Relais**.
2. Copie le paquet sur la VM (OpenSSH est inclus dans Windows) :
   ```powershell
   scp .\paranoid-relay_1.1.2_amd64.deb <utilisateur>@192.168.1.252:/tmp/
   ```
3. Installe-le sur la VM, en `su -` (configuration, clé et base sont gardées, le relais redémarre) :
   ```bash
   apt install /tmp/paranoid-relay_1.1.2_amd64.deb
   paranoid-relay status
   ```

Plafond global, port, pare-feu et SSH se règlent avec `paranoid-relay config` (ou en modifiant `/etc/paranoid-relay/relay.conf`, puis `paranoid-relay apply`). Tailles acceptées : un nombre d'octets, ou avec un suffixe `K`, `M`, `G`, `T` (puissances de 1024) ; `0` désactive le contrôle. Le fichier SQLite ne rétrécit pas tout seul après une vague de messages. Pour le réduire, service arrêté : `apt install sqlite3`, puis `sqlite3 /var/lib/paranoid-relay/relay.db VACUUM`.

## En cas de problème

| Symptôme | Cause probable |
|---|---|
| « Relais injoignable » | Adresse LAN utilisée hors du Wi-Fi, redirection de la box absente, ou relais arrêté (`paranoid-relay status`). |
| « empreinte du relais inattendue » | L'empreinte saisie ne correspond pas au certificat du relais. |
| « version d'invitation non prise en charge » | L'autre côté n'a pas la v1.0 (appli ou `relaycli`). |
| Le téléphone reste « En attente du scan » | L'autre n'a pas encore scanné, ou son envoi a échoué (il voit une erreur) : il recommence le scan. |
| Message « rejeté » côté PC | Deux `relaycli` ouverts en même temps, ou session désynchronisée : réinitialiser la session ou refaire l'appairage. |
| « Données illisibles » au démarrage | La clé Keystore a disparu (appli désinstallée, par exemple). L'identité est perdue, c'est voulu. |
| L'APK refuse de s'installer par-dessus | Signature différente (APK d'une compilation sans les secrets). Il faut désinstaller, ce qui perd l'identité. |
| « Problème lors de l'analyse du package » | Le téléphone a une version d'Android antérieure à 13. |
| « le relais est plein » | Plafond global atteint : `paranoid-relay status`. Relever `MAX_DB` (`paranoid-relay config`) ou libérer de la place. |
