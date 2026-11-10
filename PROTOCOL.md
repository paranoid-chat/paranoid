# Protocole relais v0

Messagerie sans compte : l'identité est une paire de clés générée sur le téléphone. Les relais sont interchangeables et ne stockent que des blobs chiffrés. Ce document décrit uniquement le protocole client/relais et le contenu du QR. La cryptographie de messagerie (ratchet) est hors périmètre : le relais traite les blobs comme opaques.

## 1. Paramètres v1

| Paramètre | Valeur | Remarque |
|---|---|---|
| Taille d'un blob | 2048 octets exactement | Le client complète (padding) ; autre taille = rejet |
| Quota par boîte | 100 messages en attente | Compte uniquement les messages non acquittés |
| TTL d'un message | 7 jours | Un message non relevé est supprimé ; la boîte reste |
| Expiration d'une boîte | 90 jours sans relève authentifiée | À valider |
| Tolérance d'horodatage | ±60 s | Anti-rejeu avec nonce |
| TLS | 1.3 minimum | Certificat auto-signé accepté en dev (voir §6) |

L'historique d'une conversation est stocké uniquement sur les téléphones. Le relais ne conserve que les messages en transit.

## 2. Identifiants

- `box_id` : 16 octets aléatoires. Sert à relever et acquitter.
- `write_id` : 16 octets aléatoires, distinct de `box_id`. Sert à déposer. Se transmet dans le QR.
- Clé de lecture de boîte : paire Ed25519 aléatoire, propre à chaque boîte (jamais la clé d'identité). Le relais n'en connaît que la partie publique.
- Une boîte est créée par contact et par relais.

Encodage dans les URL : base64url sans padding.

## 3. Routes

### Créer une boîte
`POST /v1/box`
- Corps : 32 octets (clé publique Ed25519 de lecture).
- Réponse 201 : 32 octets (`box_id` puis `write_id`).
- Limitation de débit par IP (voir §5).

### Déposer un message
`PUT /v1/box/{write_id}/msg`
- Corps : 2048 octets.
- Réponse 201 : 8 octets (numéro de séquence, big-endian).
- Sans authentification.

### Relever
`GET /v1/box/{box_id}/msgs?after={seq}&limit={n}` (n ≤ 50, défaut 50)
- Authentifié (voir §4).
- Réponse 200 `application/octet-stream` : suite d'enregistrements de 2064 octets :
  `seq` (8, big-endian) | `deposit_ts` (8, secondes Unix) | `blob` (2048).
- En-tête `X-More: 1` s'il reste des messages après la page.

### Acquitter
`DELETE /v1/box/{box_id}/msgs?upto={seq}`
- Authentifié. Supprime tous les messages de séquence ≤ `seq`.
- Réponse 204.

### Supprimer la boîte
`DELETE /v1/box/{box_id}`
- Authentifié. Réponse 204.

## 4. Authentification des requêtes de lecture

En-têtes :
- `X-Timestamp` : secondes Unix.
- `X-Nonce` : 16 octets aléatoires, base64url.
- `X-Signature` : signature Ed25519, base64url.

Message signé (octets UTF-8, séparés par `\n`) :
`METHOD` / `PATH_AVEC_QUERY` / `X-Timestamp` / `X-Nonce` / `hex(SHA-256(corps))`

Le relais rejette (401) si l'horodatage dépasse la tolérance, si le nonce a déjà été vu dans la fenêtre, ou si la signature est invalide.

## 5. Erreurs et limites

| Code | Cas |
|---|---|
| 400 | Taille de blob incorrecte, paramètre invalide |
| 401 | Signature, horodatage ou nonce invalide |
| 404 | Boîte inconnue ou expirée |
| 429 | Limitation de débit (en-tête `Retry-After`) |
| 507 | Quota de la boîte atteint : l'expéditeur réessaie plus tard |

Limites initiales : 10 créations de boîte par heure et par IP ; 60 dépôts par minute et par `write_id`.

## 6. TLS et QR

Le client épingle le certificat du relais par l'empreinte SHA-256 du certificat DER, fournie dans le QR. Aucune autorité de certification n'est requise (adresse IP ou nom DDNS possibles).

Contenu du QR v1 (JSON compact encodé en base64url) :

```
{ "v": 1,
  "ik": <clé publique Ed25519 d'identité, 32 octets>,
  "xk": <clé publique X25519, 32 octets>,
  "r": [ { "u": "https://hote:port", "fp": <SHA-256, 32 octets>, "w": <write_id, 16 octets> } ] }
```

Le QR est **propre à un contact** : avant de l'afficher, le téléphone crée une boîte dédiée sur chaque relais listé (1 à 3), puis place les `write_id` correspondants dans le QR.

Échange v1 : double scan (A scanne B, puis B scanne A). Chaque QR doit être affiché avec `FLAG_SECURE`.

## 7. Journalisation du relais

Aucun journal d'accès applicatif. Pas de stockage d'adresses IP au-delà de la mémoire nécessaire à la limitation de débit.

## 8. Points ouverts

- Choix du protocole de messagerie (ratchet) : décidé au client CLI.
- Réveil en arrière-plan : foreground service ou UnifiedPush.
- Preuve de travail ou jeton pour la création de boîtes.
- Envoi redondant vers plusieurs relais et détection des pertes.
- Secret à usage unique pour remplacer le double scan (v2).
