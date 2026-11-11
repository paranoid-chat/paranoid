# Formats chiffrés (client)

Ces formats sont uniquement côté client : le relais ne voit qu'un bloc opaque de 2048 octets (`PROTOCOL.md`). Le premier octet porte la version du bloc :

| Version | Usage |
|---|---|
| 2 | Message du Double Ratchet |
| 3 | Bloc d'appairage, déposé une seule fois par celui qui scanne |

Chaque identité possède une clé **Ed25519** d'identité (`ik`) et une clé **X25519** statique (`xk`).

---

## Appairage en un seul scan (bloc version 3)

L'invitant affiche un QR (version 2, `PROTOCOL.md` §6) : `ik`, `xk`, relais, `write_id` de la boîte réservée au contact, et un secret `s` de 32 octets à usage unique. Celui qui scanne crée sa boîte pour l'invitant, puis dépose ce bloc dans la boîte de l'invitant :

| Champ | Taille |
|---|---|
| Version (= 3) | 1 |
| Clé publique X25519 éphémère `e` | 32 |
| Contenu chiffré | 1999 |
| Tag AES-GCM | 16 |

Contenu clair (1999 octets) : `ik` (32) ‖ `xk` (32) ‖ `write_id` de sa boîte (16) ‖ empreinte de son relais (32) ‖ longueur de l'URL (2, big-endian) ‖ URL du relais (UTF-8, 255 octets max, `https://`) ‖ signature Ed25519 (64) ‖ zéros.

| Élément | Définition |
|---|---|
| Clé et nonce | `HKDF-SHA256(ikm = X25519(e, xk_invitant) ‖ s, sel = ik_invitant ‖ e, info = "relay-pair-v1")` → clé AES-256 (32) + nonce (12) |
| Données associées | `"relay-pair-v1" ‖ ik_invitant ‖ e` |
| Signature | par la clé d'identité de celui qui scanne, sur `"relay-pair-v1/sig" ‖ ik_invitant ‖ SHA-256(s) ‖ contenu avant la signature` |

Réception chez l'invitant : tant que le contact n'est pas appairé, seul un bloc version 3 qui se déchiffre avec le secret, dont la signature est valide et dont l'`ik` n'est ni le sien ni celui d'un autre contact, est accepté. Le secret est alors effacé (usage unique) ; tout autre bloc est rejeté et acquitté.

Ce que ça garantit :
- **Confidentialité** : seul le détenteur de la clé privée `xk` de l'invitant, et du secret, lit le bloc. Le relais ne peut pas relier les deux boîtes.
- **Lien `ik` ↔ `xk`** : la signature empêche quelqu'un qui aurait photographié le QR d'annoncer l'`ik` d'un autre avec sa propre `xk`. L'empreinte affichée, calculée sur `ik`, reste donc fiable.
- **Usage unique** : un deuxième bloc, même valide, est refusé.

Limite : quelqu'un qui photographie le QR et dépose son propre bloc avant le vrai contact est accepté à sa place. La comparaison des empreintes à voix haute le révèle ; le vrai contact voit alors ses messages refusés.

---

## Double Ratchet (bloc version 2)

Implémentation de la spécification de Signal (signal.org/docs/specifications/doubleratchet), avec les adaptations ci-dessous.

### Démarrage, sans X3DH

L'appairage donne à chacun les clés statiques de l'autre, vérifiées par la comparaison des empreintes.

- **Secret initial** : `SK = HKDF-SHA256(X25519(xk_moi, xk_lui), sel = ik_bas ‖ ik_haut ‖ xk_bas ‖ xk_haut, info = "relay-dr-v1/sk")`. « bas » et « haut » sont les deux identités triées par ordre des octets de `ik`.
- **Rôles fixes** : l'**initiateur** est celui dont `ik` est le plus petit. Il démarre comme « Alice » dans la spécification : nouvelle paire DH, chaîne d'envoi = `KDF_RK(SK, DH(paire, xk_lui))`. Le **répondeur** démarre comme « Bob » : sa paire DH initiale est sa clé statique `xk`.
- **Chaîne de démarrage** : dans la spécification, Bob ne peut pas écrire avant d'avoir reçu un message. Ici, si le répondeur écrit en premier, il génère une clé éphémère `e` (la partie privée est effacée aussitôt) et utilise une chaîne à sens unique `CK = HKDF(X25519(e, xk_initiateur), sel = SK, info = "relay-dr-v1/boot")`. Dès qu'il reçoit le premier message de l'initiateur, il passe au ratchet et abandonne cette chaîne. Les deux peuvent donc écrire en même temps dès l'appairage terminé.

### Dérivations

| Fonction | Définition |
|---|---|
| `KDF_RK(rk, dh)` | `HKDF-SHA256(ikm = dh, sel = rk, info = "relay-dr-v1/rk")` → 64 octets : nouvelle `rk` et clé de chaîne |
| `KDF_CK(ck)` | clé de message = `HMAC-SHA256(ck, 0x01)`, nouvelle chaîne = `HMAC-SHA256(ck, 0x02)` |
| Chiffrement | `HKDF-SHA256(ikm = clé de message, info = "relay-dr-v1/msg")` → clé AES-256 + nonce de 12 octets, puis AES-256-GCM |
| Données associées | `"relay-dr-v1/ad" ‖ ik_bas ‖ ik_haut ‖ en-tête` |

### Bloc (2048 octets)

| Champ | Taille |
|---|---|
| Version (= 2) | 1 |
| Type (0 = ratchet, 1 = démarrage) | 1 |
| Clé publique DH courante de l'expéditeur | 32 |
| PN (longueur de la chaîne d'envoi précédente) | 4 |
| N (numéro du message dans la chaîne) | 4 |
| Contenu chiffré | 1990 |
| Tag AES-GCM | 16 |

Contenu clair (1990 octets) : type (1) ‖ identifiant aléatoire (16) ‖ horodatage de l'expéditeur (8) ‖ longueur (2) ‖ texte UTF-8, puis zéros. Le texte fait **1963 octets** au maximum.

Types :
- **1 — texte.**
- **2 — alerte « bouton rouge »** (corps vide). L'expéditeur a saisi son code de destruction : sa clé privée est détruite. Le destinataire marque le contact comme « brûlé », affiche « X a utilisé le bouton rouge et ne pourra plus consulter cette conversation », et n'envoie plus rien à ce contact. L'alerte passe par le ratchet comme un message ordinaire : le relais ne la distingue pas d'un texte.
- **3 — changement de relais** (corps vide). L'expéditeur change de relais : son identité est effacée juste après l'envoi, sans migration. Le destinataire ferme la conversation comme pour le type 2 (plus aucun envoi), affiche « X a changé de relais », et doit refaire l'appairage avec la nouvelle identité.

### Règles de robustesse

- **Pas de modification sur échec** : la réception travaille sur une copie de la session, qui n'est conservée que si le déchiffrement réussit. Un bloc forgé ou altéré ne désynchronise rien.
- **Messages en désordre ou perdus** : les clés des messages manquants sont mises de côté, avec 1 000 au plus par message reçu et 2 000 au total.
- **Pas de réutilisation de clé** : à l'envoi, la session est enregistrée **avant** le dépôt. Si le dépôt échoue, le destinataire voit simplement un message manquant.
- **Rejeu** : une clé de message est effacée après usage, donc un bloc rejoué est refusé. Après la bascule du répondeur sur le ratchet, l'initiateur refuse tout nouveau bloc de démarrage, car la chaîne se recalcule depuis les clés statiques.

### Ce que ça garantit

- **Confidentialité et intégrité** : AES-256-GCM avec une clé unique par message.
- **Authenticité** : seuls les détenteurs de `SK` (donc d'une des deux clés `xk` privées) peuvent produire un bloc valide. Un tiers qui a le `write_id` peut déposer, mais son bloc est refusé.
- **Confidentialité persistante** : une fois lu, un message n'est plus déchiffrable avec l'état actuel.
- **Sécurité après compromission** : un vol de l'état d'une session ne permet plus de lire la conversation après un aller-retour complet (testé : `TestDRPostCompromiseSecurity`).
- **Déni plausible** : pas de signature, donc pas de preuve opposable à un tiers.

### Limites connues

- **Premiers messages** : avant le premier aller-retour, les messages ne dépendent que des clés statiques. Un vol ultérieur de la clé `xk` permet de lire ceux qu'un relais aurait copiés. Ensuite, le ratchet prend le relais. Dans Signal, cette fenêtre est réduite par la rotation des prekeys et les prekeys à usage unique.
- **Usurpation après vol de clé (KCI)** : celui qui vole la clé `xk` d'Alice peut se faire passer pour Bob **auprès d'Alice**, car `SK` dépend seulement des clés statiques. C'est une propriété classique des échanges statique-statique.
- **En-tête en clair** : le relais voit la clé DH courante et les compteurs, donc qui écrit à son tour et combien. Il connaît déjà le nombre de messages par boîte. La spécification prévoit une variante à en-têtes chiffrés.
- **Réinitialisation** : après `reset` d'un côté, l'autre doit faire de même ; après `remove`, les deux refont l'appairage. Sinon, la session reste désynchronisée.
- **Crypto maison** : l'implémentation suit la spécification mais n'a pas été relue par un tiers. À faire avant toute diffusion.

