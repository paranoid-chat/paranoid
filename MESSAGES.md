# Formats de message (client)

Ces formats sont uniquement côté client : le relais ne voit qu'un bloc opaque de 2048 octets (`PROTOCOL.md`). Le premier octet porte la version.

- **v2 (actuel)** : Double Ratchet. C'est le seul format envoyé.
- **v1 (ancien)** : chiffrement par message + signature Ed25519. Encore accepté en réception, à supprimer.

Chaque identité possède une clé **Ed25519** d'identité (`ik`) et une clé **X25519** statique (`xk`). Les deux clés publiques sont dans l'invitation (le QR).

---

## v2 : Double Ratchet

Implémentation de la spécification de Signal (signal.org/docs/specifications/doubleratchet), avec les adaptations ci-dessous.

### Démarrage, sans X3DH

Le double scan donne à chacun les clés statiques de l'autre, vérifiées en face à face.

- **Secret initial** : `SK = HKDF-SHA256(X25519(xk_moi, xk_lui), sel = ik_bas ‖ ik_haut ‖ xk_bas ‖ xk_haut, info = "relay-dr-v1/sk")`. « bas » et « haut » sont les deux identités triées par ordre des octets de `ik`.
- **Rôles fixes** : l'**initiateur** est celui dont `ik` est le plus petit. Il démarre comme « Alice » dans la spécification : nouvelle paire DH, chaîne d'envoi = `KDF_RK(SK, DH(paire, xk_lui))`. Le **répondeur** démarre comme « Bob » : sa paire DH initiale est sa clé statique `xk`.
- **Chaîne de démarrage** : dans la spécification, Bob ne peut pas écrire avant d'avoir reçu un message. Ici, si le répondeur écrit en premier, il génère une clé éphémère `e` (la partie privée est effacée aussitôt) et utilise une chaîne à sens unique `CK = HKDF(X25519(e, xk_initiateur), sel = SK, info = "relay-dr-v1/boot")`. Dès qu'il reçoit le premier message de l'initiateur, il passe au ratchet et abandonne cette chaîne. Les deux peuvent donc écrire en même temps dès l'échange de QR.

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
- **Réinitialisation** : après `reset` ou `remove` d'un côté, l'autre côté doit faire de même et les deux doivent refaire l'échange, sinon la session reste désynchronisée.
- **Crypto maison** : l'implémentation suit la spécification mais n'a pas été relue par un tiers. À faire avant toute diffusion.

---

## v1 (ancien format, réception uniquement)

Bloc : version (= 1) ‖ X25519 éphémère (32) ‖ nonce (12) ‖ chiffré AES-256-GCM (1987 + tag 16). Clé = `HKDF-SHA256(X25519(éphémère, xk destinataire), sel = éph ‖ xk destinataire, info = "relay-msg-v1")`. Contenu : signature Ed25519 de l'expéditeur (64) ‖ type ‖ identifiant ‖ horodatage ‖ longueur ‖ texte. La signature couvre `"relay-msg-v1/sig" ‖ ik destinataire ‖ contenu`.

Pas de confidentialité persistante côté destinataire, pas de sécurité après compromission, et non-répudiation (la signature est une preuve).
