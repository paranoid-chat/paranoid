package main

// Notifications Web Push (RFC 8030) pour UnifiedPush : après chaque dépôt, le relais envoie au
// point de notification de la boîte un réveil chiffré (RFC 8291, aes128gcm) et authentifié
// (VAPID, RFC 8292). Le contenu est un octet sans signification : ni l'expéditeur, ni le
// message, ni la boîte n'y figurent. Le téléphone relève ensuite sa boîte normalement.

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	pushRecordSize = 4096
	pushTTL        = 86400
	pushCoalesce   = 2 * time.Second // au plus un réveil par boîte toutes les 2 s
	pushMaxURL     = 512
	pushParallel   = 32
)

type Pusher struct {
	key          *ecdsa.PrivateKey
	pub          []byte // clé publique VAPID non compressée (65 octets)
	sub          string
	client       *http.Client
	allowHTTP    bool
	allowPrivate bool
	sem          chan struct{}
	now          func() time.Time

	mu   sync.Mutex
	last map[string]time.Time
}

// NewPusher charge (ou crée) la clé VAPID du relais.
func NewPusher(keyPath, sub string, allowPrivate, allowHTTP bool) (*Pusher, error) {
	key, err := loadOrCreateVapid(keyPath)
	if err != nil {
		return nil, err
	}
	p := &Pusher{
		key:          key,
		sub:          sub,
		allowHTTP:    allowHTTP,
		allowPrivate: allowPrivate,
		sem:          make(chan struct{}, pushParallel),
		now:          time.Now,
		last:         map[string]time.Time{},
	}
	ecdhKey, err := key.PublicKey.ECDH()
	if err != nil {
		return nil, err
	}
	p.pub = ecdhKey.Bytes()
	p.client = p.newClient()
	return p, nil
}

func loadOrCreateVapid(path string) (*ecdsa.PrivateKey, error) {
	if data, err := os.ReadFile(path); err == nil {
		block, _ := pem.Decode(data)
		if block == nil {
			return nil, fmt.Errorf("clé VAPID illisible : %s", path)
		}
		k, err := x509.ParsePKCS8PrivateKey(block.Bytes)
		if err != nil {
			return nil, err
		}
		ek, ok := k.(*ecdsa.PrivateKey)
		if !ok || ek.Curve != elliptic.P256() {
			return nil, fmt.Errorf("clé VAPID : P-256 attendue (%s)", path)
		}
		return ek, nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	der, err := x509.MarshalPKCS8PrivateKey(k)
	if err != nil {
		return nil, err
	}
	if err := os.WriteFile(path, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), 0o600); err != nil {
		return nil, fmt.Errorf("écriture de la clé VAPID : %w", err)
	}
	return k, nil
}

// PublicKey est la clé VAPID à transmettre au distributeur UnifiedPush lors de l'enregistrement.
func (p *Pusher) PublicKey() []byte { return p.pub }

// forbiddenIP : le relais refuse de contacter son réseau local (protection SSRF), sauf option.
func forbiddenIP(ip net.IP) bool {
	if ip == nil {
		return true
	}
	cgnat := &net.IPNet{IP: net.IPv4(100, 64, 0, 0), Mask: net.CIDRMask(10, 32)}
	return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() ||
		ip.IsInterfaceLocalMulticast() || ip.IsMulticast() || ip.IsUnspecified() || cgnat.Contains(ip)
}

func (p *Pusher) newClient() *http.Client {
	dialer := &net.Dialer{
		Timeout: 5 * time.Second,
		// Contrôle après résolution DNS : empêche aussi le contournement par rebinding.
		Control: func(network, address string, c syscall.RawConn) error {
			if p.allowPrivate {
				return nil
			}
			host, _, err := net.SplitHostPort(address)
			if err != nil {
				return err
			}
			if forbiddenIP(net.ParseIP(host)) {
				return fmt.Errorf("adresse de notification interdite : %s", host)
			}
			return nil
		},
	}
	tr := &http.Transport{
		Proxy:               nil,
		DialContext:         dialer.DialContext,
		TLSHandshakeTimeout: 5 * time.Second,
		MaxIdleConns:        16,
		IdleConnTimeout:     60 * time.Second,
		ForceAttemptHTTP2:   true,
	}
	return &http.Client{
		Transport:     tr,
		Timeout:       10 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
}

// Validate vérifie un point de notification avant de l'enregistrer.
func (p *Pusher) Validate(t PushTarget) error {
	if len(t.URL) == 0 || len(t.URL) > pushMaxURL {
		return errors.New("URL de notification invalide")
	}
	u, err := url.Parse(t.URL)
	if err != nil || u.Host == "" || u.User != nil || u.Fragment != "" {
		return errors.New("URL de notification invalide")
	}
	if u.Scheme != "https" && !(p.allowHTTP && u.Scheme == "http") {
		return errors.New("URL de notification : https obligatoire")
	}
	if _, err := ecdh.P256().NewPublicKey(t.P256dh); err != nil {
		return errors.New("clé p256dh invalide")
	}
	if len(t.Auth) != 16 {
		return errors.New("secret auth invalide")
	}
	return nil
}

// Notify envoie un réveil en arrière-plan. Sans attente : un réveil perdu n'est pas grave, le
// téléphone relève aussi sa boîte à chaque ouverture de l'appli.
func (p *Pusher) Notify(t PushTarget, key string) {
	now := p.now()
	p.mu.Lock()
	if last, ok := p.last[key]; ok && now.Sub(last) < pushCoalesce {
		p.mu.Unlock()
		return
	}
	p.last[key] = now
	if len(p.last) > 10000 {
		for k, v := range p.last {
			if now.Sub(v) > pushCoalesce {
				delete(p.last, k)
			}
		}
	}
	p.mu.Unlock()
	select {
	case p.sem <- struct{}{}:
	default:
		return // trop d'envois en cours : on abandonne celui-ci
	}
	go func() {
		defer func() { <-p.sem }()
		_ = p.Send(context.Background(), t)
	}()
}

// Send envoie un réveil chiffré et attend la réponse du service de notification.
func (p *Pusher) Send(ctx context.Context, t PushTarget) error {
	body, err := encryptWebPush(t.P256dh, t.Auth, []byte{1}, nil, nil)
	if err != nil {
		return err
	}
	u, err := url.Parse(t.URL)
	if err != nil {
		return err
	}
	jwt, err := p.vapidJWT(u.Scheme + "://" + u.Host)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, t.URL, strings.NewReader(string(body)))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/octet-stream")
	req.Header.Set("Content-Encoding", "aes128gcm")
	req.Header.Set("TTL", fmt.Sprint(pushTTL))
	req.Header.Set("Urgency", "high")
	req.Header.Set("Authorization", "vapid t="+jwt+", k="+base64.RawURLEncoding.EncodeToString(p.pub))
	resp, err := p.client.Do(req)
	if err != nil {
		return err
	}
	resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("service de notification : %s", resp.Status)
	}
	return nil
}

// vapidJWT signe le jeton VAPID (ES256) pour l'origine du service de notification.
func (p *Pusher) vapidJWT(aud string) (string, error) {
	hdr := base64.RawURLEncoding.EncodeToString([]byte(`{"typ":"JWT","alg":"ES256"}`))
	claims, err := json.Marshal(map[string]any{"aud": aud, "exp": p.now().Add(12 * time.Hour).Unix(), "sub": p.sub})
	if err != nil {
		return "", err
	}
	signing := hdr + "." + base64.RawURLEncoding.EncodeToString(claims)
	sum := sha256.Sum256([]byte(signing))
	r, s, err := ecdsa.Sign(rand.Reader, p.key, sum[:])
	if err != nil {
		return "", err
	}
	sig := make([]byte, 64)
	r.FillBytes(sig[:32])
	s.FillBytes(sig[32:])
	return signing + "." + base64.RawURLEncoding.EncodeToString(sig), nil
}

// encryptWebPush chiffre plaintext selon RFC 8291 (un seul enregistrement aes128gcm).
// asPriv et salt ne sont fournis que par les tests (vecteurs de la RFC) ; sinon aléatoires.
func encryptWebPush(uaPub, authSecret, plaintext []byte, asPriv *ecdh.PrivateKey, salt []byte) ([]byte, error) {
	ua, err := ecdh.P256().NewPublicKey(uaPub)
	if err != nil {
		return nil, err
	}
	if len(authSecret) != 16 {
		return nil, errors.New("secret auth invalide")
	}
	if asPriv == nil {
		if asPriv, err = ecdh.P256().GenerateKey(rand.Reader); err != nil {
			return nil, err
		}
	}
	if salt == nil {
		salt = make([]byte, 16)
		if _, err := rand.Read(salt); err != nil {
			return nil, err
		}
	}
	shared, err := asPriv.ECDH(ua)
	if err != nil {
		return nil, err
	}
	asPub := asPriv.PublicKey().Bytes()

	prkKey, err := hkdf.Extract(sha256.New, shared, authSecret)
	if err != nil {
		return nil, err
	}
	keyInfo := "WebPush: info\x00" + string(uaPub) + string(asPub)
	ikm, err := hkdf.Expand(sha256.New, prkKey, keyInfo, 32)
	if err != nil {
		return nil, err
	}
	prk, err := hkdf.Extract(sha256.New, ikm, salt)
	if err != nil {
		return nil, err
	}
	cek, err := hkdf.Expand(sha256.New, prk, "Content-Encoding: aes128gcm\x00", 16)
	if err != nil {
		return nil, err
	}
	nonce, err := hkdf.Expand(sha256.New, prk, "Content-Encoding: nonce\x00", 12)
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(cek)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	// Dernier (et seul) enregistrement : délimiteur 0x02, sans remplissage.
	padded := append(append([]byte(nil), plaintext...), 0x02)
	if len(padded)+16 > pushRecordSize {
		return nil, errors.New("contenu trop long")
	}

	out := make([]byte, 0, 16+4+1+len(asPub)+len(padded)+16)
	out = append(out, salt...)
	out = binary.BigEndian.AppendUint32(out, pushRecordSize)
	out = append(out, byte(len(asPub)))
	out = append(out, asPub...)
	return gcm.Seal(out, nonce, padded, nil), nil
}

// verifyES256 sert aux tests : vérifie une signature JWT ES256 (r ‖ s).
func verifyES256(pub *ecdsa.PublicKey, signing string, sig []byte) bool {
	if len(sig) != 64 {
		return false
	}
	sum := sha256.Sum256([]byte(signing))
	return ecdsa.Verify(pub, sum[:], new(big.Int).SetBytes(sig[:32]), new(big.Int).SetBytes(sig[32:]))
}
