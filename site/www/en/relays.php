<?php
// "Relays" page (EN). The site header and footer are to be taken from build.py.
declare(strict_types=1);
require __DIR__ . '/../inc/relays.php';
header('Cache-Control: no-cache');
?><!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Relays — Paranoid</title>
<link rel="stylesheet" href="/style.css">
</head>
<body>
<main>
<h1>Relays</h1>
<p>Paranoid relays installed with the official package or ISO image whose operator agreed to publish them. To use one, enter its address and fingerprint in the app, when creating the identity or in <em>Change relay</em>.</p>
<p>Every relay sends a signal every hour, signed with its certificate key: the fingerprint shown really belongs to the relay that wrote, and the address is the one the signal came from. “Online” means a signal arrived within the last 2 hours. It does not guarantee the relay is reachable from your network, nor that its operator can be trusted: the relay cannot read messages, but it sees IP addresses and connection times.</p>
<?= relays_render(true) ?>
<p>Relay operators: to remove yours, run <code>paranoid-relay config</code> and answer No to publishing in the directory. A relay with no signal for 30 days disappears from the list.</p>
</main>
</body>
</html>
