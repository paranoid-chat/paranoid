<?php
// Annuaire des relais Paranoid : réception des signaux signés et affichage.
// Données dans un fichier JSON hors de la racine web (RELAYS_DATA).
declare(strict_types=1);

const RELAYS_DATA = __DIR__ . '/../../paranoid-data/relays.json';
const RELAYS_MAX = 1000;            // relais enregistrés au maximum
const RELAYS_ONLINE = 7200;         // « en ligne » si dernier signal < 2 h
const RELAYS_EXPIRE = 30 * 86400;   // retiré après 30 jours sans signal
const RELAYS_SKEW = 600;            // horloge du relais : ±10 min
const RELAYS_RATE = 20;             // signaux par heure et par IP

function relays_load($fh): array
{
    rewind($fh);
    $raw = stream_get_contents($fh);
    $d = $raw ? json_decode($raw, true) : null;
    if (!is_array($d)) {
        $d = [];
    }
    return $d + ['relays' => [], 'rate' => []];
}

function relays_save($fh, array $d): void
{
    ftruncate($fh, 0);
    rewind($fh);
    fwrite($fh, json_encode($d, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE));
    fflush($fh);
}

function relays_open(bool $write)
{
    $dir = dirname(RELAYS_DATA);
    if (!is_dir($dir)) {
        @mkdir($dir, 0700, true);
    }
    $fh = @fopen(RELAYS_DATA, $write ? 'c+' : 'r');
    if ($fh === false) {
        return null;
    }
    flock($fh, $write ? LOCK_EX : LOCK_SH);
    return $fh;
}

function relays_fp_format(string $hex): string
{
    return implode(':', str_split(strtoupper($hex), 2));
}

function relays_valid_host(string $h): bool
{
    if (filter_var($h, FILTER_VALIDATE_IP, FILTER_FLAG_IPV4)) {
        return true;
    }
    return strlen($h) <= 253 && (bool) preg_match('/^(?=.*[A-Za-z])[A-Za-z0-9-]{1,63}(\.[A-Za-z0-9-]{1,63})+$/', $h);
}

// Retourne [code HTTP, texte].
function relays_announce(array $p, string $ip, int $now): array
{
    $f = [];
    foreach (['v', 'action', 'name', 'host', 'port', 'version', 'ts', 'cert', 'sig'] as $k) {
        if (!isset($p[$k]) || !is_string($p[$k])) {
            return [400, "champ manquant : $k"];
        }
        $f[$k] = $p[$k];
    }
    if ($f['v'] !== '1') {
        return [400, 'version de signal inconnue'];
    }
    if ($f['action'] !== 'up' && $f['action'] !== 'remove') {
        return [400, 'action inconnue'];
    }
    if (!preg_match('/^[A-Za-z0-9._-]{1,32}$/', $f['name'])
        || !relays_valid_host($f['host'])
        || !preg_match('/^[0-9]{1,5}$/', $f['port']) || (int) $f['port'] < 1 || (int) $f['port'] > 65535
        || !preg_match('/^[0-9A-Za-z.+~-]{1,20}$/', $f['version'])
        || !preg_match('/^[0-9]{13}$/', $f['ts'])
        || strlen($f['cert']) > 4096 || strlen($f['sig']) > 512) {
        return [400, 'champ invalide'];
    }
    $ts = (int) $f['ts']; // millisecondes
    if (abs($now - intdiv($ts, 1000)) > RELAYS_SKEW) {
        return [400, "horloge du relais décalée de plus de 10 minutes"];
    }
    if (!filter_var($ip, FILTER_VALIDATE_IP, FILTER_FLAG_IPV4)) {
        return [400, 'signal accepté en IPv4 seulement'];
    }

    // Signature : prouve que l'expéditeur détient la clé du certificat annoncé.
    $cert = @openssl_x509_read($f['cert']);
    $pub = $cert ? @openssl_pkey_get_public($cert) : false;
    $sig = base64_decode($f['sig'], true);
    if (!$cert || !$pub || $sig === false) {
        return [400, 'certificat ou signature illisible'];
    }
    $msg = "paranoid-relay-announce-v1\n{$f['action']}\n{$f['name']}\n{$f['host']}\n{$f['port']}\n{$f['version']}\n{$f['ts']}\n";
    if (openssl_verify($msg, $sig, $pub, OPENSSL_ALGO_SHA256) !== 1) {
        return [403, 'signature invalide'];
    }
    $fp = relays_fp_format(openssl_x509_fingerprint($cert, 'sha256'));

    // L'adresse publiée doit être celle d'où vient le signal.
    if ($f['action'] === 'up') {
        $ips = filter_var($f['host'], FILTER_VALIDATE_IP) ? [$f['host']] : (@gethostbynamel($f['host']) ?: []);
        if (!in_array($ip, $ips, true)) {
            return [403, "l'adresse publique {$f['host']} ne correspond pas à l'IP d'envoi $ip"];
        }
    }

    $fh = relays_open(true);
    if (!$fh) {
        return [500, 'stockage indisponible'];
    }
    $d = relays_load($fh);

    $rk = hash('sha256', $ip);
    $win = intdiv($now, 3600);
    foreach ($d['rate'] as $k => $r) {
        if ($r[0] !== $win) {
            unset($d['rate'][$k]);
        }
    }
    $n = ($d['rate'][$rk][1] ?? 0) + 1;
    $d['rate'][$rk] = [$win, $n];
    if ($n > RELAYS_RATE) {
        relays_save($fh, $d);
        fclose($fh);
        return [429, 'trop de signaux, réessayer dans une heure'];
    }

    foreach ($d['relays'] as $k => $r) {
        if ($now - $r['last_seen'] > RELAYS_EXPIRE) {
            unset($d['relays'][$k]);
        }
    }
    $old = $d['relays'][$fp] ?? null;
    if ($old && $ts <= $old['last_ts']) {
        relays_save($fh, $d);
        fclose($fh);
        return [409, 'signal rejoué ou plus ancien que le précédent'];
    }

    if ($f['action'] === 'remove') {
        unset($d['relays'][$fp]);
        $out = [200, "retiré de l'annuaire : $fp"];
    } elseif (!$old && count($d['relays']) >= RELAYS_MAX) {
        $out = [507, 'annuaire plein'];
    } else {
        $d['relays'][$fp] = [
            'name' => $f['name'],
            'host' => $f['host'],
            'port' => (int) $f['port'],
            'ip' => $ip,
            'version' => $f['version'],
            'first_seen' => $old['first_seen'] ?? $now,
            'last_seen' => $now,
            'last_ts' => $ts,
        ];
        $out = [200, "enregistré : $fp"];
    }
    relays_save($fh, $d);
    fclose($fh);
    return $out;
}

function relays_list(int $now): array
{
    $fh = relays_open(false);
    if (!$fh) {
        return [];
    }
    $d = relays_load($fh);
    fclose($fh);
    $out = [];
    foreach ($d['relays'] as $fp => $r) {
        if ($now - $r['last_seen'] > RELAYS_EXPIRE) {
            continue;
        }
        $r['fp'] = $fp;
        $r['online'] = $now - $r['last_seen'] < RELAYS_ONLINE;
        $out[] = $r;
    }
    usort($out, function ($a, $b) {
        return [$b['online'], $b['last_seen']] <=> [$a['online'], $a['last_seen']];
    });
    return $out;
}

function relays_ago(int $s, bool $en): string
{
    if ($s < 120) {
        return $en ? 'just now' : "à l'instant";
    }
    if ($s < 7200) {
        $m = intdiv($s, 60);
        return $en ? "$m min ago" : "il y a $m min";
    }
    if ($s < 172800) {
        $h = intdiv($s, 3600);
        return $en ? "$h h ago" : "il y a $h h";
    }
    $j = intdiv($s, 86400);
    return $en ? "$j days ago" : "il y a $j jours";
}

// Tableau HTML des relais, sans JavaScript ni ressource externe.
function relays_render(bool $en): string
{
    $now = time();
    $list = relays_list($now);
    $e = function (string $s): string {
        return htmlspecialchars($s, ENT_QUOTES | ENT_HTML5, 'UTF-8');
    };
    if (!$list) {
        return '<p>' . ($en ? 'No relay has announced itself yet.' : "Aucun relais ne s'est encore annoncé.") . '</p>';
    }
    $L = $en
        ? ['Name', 'Address', 'IP', 'SHA-256 fingerprint', 'Version', 'Last signal', 'Status']
        : ['Nom', 'Adresse', 'IP', 'Empreinte SHA-256', 'Version', 'Dernier signal', 'État'];
    $h = '<div class="table-scroll"><table class="relays-table"><thead><tr>';
    foreach ($L as $l) {
        $h .= '<th>' . $e($l) . '</th>';
    }
    $h .= '</tr></thead><tbody>';
    foreach ($list as $r) {
        $url = 'https://' . $r['host'] . ($r['port'] === 443 ? '' : ':' . $r['port']);
        $fp = $r['fp'];
        $when = gmdate('Y-m-d H:i', $r['last_seen']) . ' UTC';
        $cells = [
            $e($r['name']),
            '<code>' . $e($url) . '</code>',
            '<code>' . $e($r['ip']) . '</code>',
            '<code class="fp">' . $e(substr($fp, 0, 47)) . '<br>' . $e(substr($fp, 48)) . '</code>',
            $e($r['version']),
            '<time datetime="' . gmdate('c', $r['last_seen']) . '" title="' . $when . '">' . $e(relays_ago($now - $r['last_seen'], $en)) . '</time>',
            $r['online'] ? '<span class="relay-up">' . ($en ? 'Online' : 'En ligne') . '</span>'
                         : '<span class="relay-down">' . ($en ? 'Offline' : 'Hors ligne') . '</span>',
        ];
        $h .= '<tr>';
        foreach ($cells as $i => $c) {
            $h .= '<td data-label="' . $e($L[$i]) . '">' . $c . '</td>';
        }
        $h .= '</tr>';
    }
    return $h . '</tbody></table></div>';
}

// Même liste en JSON pour l'appli : relais.php?format=json (ou en/relays.php?format=json) ;
// relais.php?format=myip donne l'IP publique du demandeur à l'assistant du relais.
// Les pages incluent ce fichier avant toute sortie : la réponse JSON remplace alors la page HTML.
function relays_json(): void
{
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
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    header('X-Content-Type-Options: nosniff');
    echo json_encode(['v' => 1, 'relays' => $out], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
}

if (PHP_SAPI !== 'cli' && ($_SERVER['REQUEST_METHOD'] ?? '') === 'GET') {
    $format = $_GET['format'] ?? '';
    if ($format === 'json') {
        relays_json();
        exit;
    }
    // Adresse IPv4 publique du demandeur, pour l'assistant du relais (« Adresse publique »). Rien n'est stocké.
    if ($format === 'myip') {
        header('Content-Type: text/plain; charset=utf-8');
        header('Cache-Control: no-store');
        echo (string) ($_SERVER['REMOTE_ADDR'] ?? '');
        exit;
    }
}
