<?php
// Page « Relais » (FR). L'en-tête et le pied du site sont à reprendre de build.py.
declare(strict_types=1);
require __DIR__ . '/inc/relays.php';
header('Cache-Control: no-cache');
?><!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Relais — Paranoid</title>
<link rel="stylesheet" href="/style.css">
</head>
<body>
<main>
<h1>Relais</h1>
<p>Relais Paranoid installés avec le paquet officiel ou l'image ISO et dont l'opérateur a accepté la publication. Pour en utiliser un, saisissez son adresse et son empreinte dans l'appli, à la création de l'identité ou dans <em>Changer de relais</em>.</p>
<p>Chaque relais envoie toutes les heures un signal signé avec la clé de son certificat : l'empreinte affichée est donc bien celle du relais qui écrit, et l'adresse est celle d'où vient le signal. « En ligne » signifie qu'un signal est arrivé dans les 2 dernières heures. Cela ne garantit pas que le relais est joignable depuis votre réseau, ni que son opérateur est digne de confiance : le relais ne peut pas lire les messages, mais il voit les adresses IP et les horaires de connexion.</p>
<?= relays_render(false) ?>
<p>Opérateur d'un relais : pour le retirer, <code>paranoid-relay config</code> puis répondre Non à la publication dans l'annuaire. Un relais sans signal depuis 30 jours disparaît de la liste.</p>
</main>
</body>
</html>
