<?php
// Annuaire au format JSON pour l'appli (« Changer de relais » → « Afficher les relais publics »).
declare(strict_types=1);
require __DIR__ . '/../inc/relays.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

$out = [];
foreach (relays_list(time()) as $r) {
    $out[] = [
        'name' => $r['name'],
        'url' => 'https://' . $r['host'] . ($r['port'] === 443 ? '' : ':' . $r['port']),
        'fp' => $r['fp'],
        'version' => $r['version'],
        'last_seen' => $r['last_seen'],
        'online' => $r['online'],
    ];
}
echo json_encode(['v' => 1, 'relays' => $out], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
