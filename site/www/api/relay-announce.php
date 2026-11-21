<?php
declare(strict_types=1);
require __DIR__ . '/../inc/relays.php';

header('Content-Type: text/plain; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    http_response_code(405);
    header('Allow: POST');
    exit("POST uniquement\n");
}
[$code, $text] = relays_announce($_POST, (string) ($_SERVER['REMOTE_ADDR'] ?? ''), time());
http_response_code($code);
echo $text, "\n";
