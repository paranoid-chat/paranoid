# Annuaire des relais (getparanoid.fr)

Fichiers PHP à intégrer au site getparanoid.fr (hébergement mutualisé OVH, PHP 7.4 ou plus récent, extension `openssl`). Aucun JavaScript, aucune base de données : un fichier JSON verrouillé par `flock`.

| Fichier | Rôle |
|---|---|
| `www/api/relay-announce.php` | Reçoit les signaux `POST` envoyés par `paranoid-relay announce` |
| `www/inc/relays.php` | Vérification, stockage, rendu du tableau (`relays_render(false)` en français, `true` en anglais) |
| `www/inc/.htaccess` | `Require all denied` : la bibliothèque n'est pas servie |
| `www/relais.php`, `www/en/relays.php` | Pages « Relais » / « Relays » : texte + tableau. En-tête, pied et menu à reprendre du gabarit de `build.py` |

Données : `paranoid-data/relays.json`, **hors de `www/`** (à la racine FTP, créé par PHP au premier signal). Si l'hébergement refuse d'écrire hors de `www/`, changer `RELAYS_DATA` dans `inc/relays.php` vers un dossier sous `www/` protégé par un `.htaccess` `Require all denied`.

## Signal

Formulaire URL-encodé, champs : `v=1`, `action` (`up` ou `remove`), `name`, `host` (adresse publique saisie dans l'appli), `port`, `version`, `ts` (millisecondes Unix), `cert` (PEM du certificat TLS du relais), `sig` (base64).

`sig` = signature ECDSA SHA-256, par la clé du certificat, de :

```
paranoid-relay-announce-v1\n{action}\n{name}\n{host}\n{port}\n{version}\n{ts}\n
```

## Contrôles côté site

| Contrôle | Refus |
|---|---|
| Champs présents et au bon format (nom `[A-Za-z0-9._-]{1,32}`, hôte IPv4 ou nom DNS, port, version) | 400 |
| Horloge du relais à ±10 min | 400 |
| Signature valide pour le certificat envoyé ; l'empreinte publiée est calculée par le site | 403 |
| `up` : `host` est l'IP d'envoi, ou un nom DNS qui résout vers elle | 403 |
| `ts` strictement plus grand que le dernier reçu pour cette empreinte (anti-rejeu) | 409 |
| 20 signaux par heure et par IP | 429 |
| 1000 relais au plus | 507 |

Un relais est identifié par son empreinte : un changement d'IP met à jour l'entrée. « En ligne » = dernier signal il y a moins de 2 h. Une entrée sans signal depuis 30 jours est supprimée. `action=remove` retire l'entrée (envoyé quand l'opérateur répond Non dans l'assistant, avant `newcert`, et à `apt remove`).

## Test local

```bash
cd site/www && php -S 127.0.0.1:8099
# sur une machine avec le paquet, PUBLIC_HOST=127.0.0.1 :
PARANOID_ANNOUNCE_URL=http://127.0.0.1:8099/api/relay-announce.php paranoid-relay announce
```
