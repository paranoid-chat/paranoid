package main

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func unb64(t *testing.T, s string) []byte {
	t.Helper()
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// decryptWebPush : déchiffrement côté client (RFC 8291), pour vérifier l'envoi.
func decryptWebPush(t *testing.T, ua *ecdh.PrivateKey, auth, body []byte) []byte {
	t.Helper()
	salt := body[:16]
	idlen := int(body[20])
	asPub := body[21 : 21+idlen]
	ct := body[21+idlen:]
	as, err := ecdh.P256().NewPublicKey(asPub)
	if err != nil {
		t.Fatal(err)
	}
	shared, _ := ua.ECDH(as)
	prkKey, _ := hkdf.Extract(sha256.New, shared, auth)
	ikm, _ := hkdf.Expand(sha256.New, prkKey, "WebPush: info\x00"+string(ua.PublicKey().Bytes())+string(asPub), 32)
	prk, _ := hkdf.Extract(sha256.New, ikm, salt)
	cek, _ := hkdf.Expand(sha256.New, prk, "Content-Encoding: aes128gcm\x00", 16)
	nonce, _ := hkdf.Expand(sha256.New, prk, "Content-Encoding: nonce\x00", 12)
	block, _ := aes.NewCipher(cek)
	gcm, _ := cipher.NewGCM(block)
	pt, err := gcm.Open(nil, nonce, ct, nil)
	if err != nil {
		t.Fatalf("déchiffrement : %v", err)
	}
	if binary.BigEndian.Uint32(body[16:20]) != pushRecordSize || pt[len(pt)-1] != 0x02 {
		t.Fatal("en-tête ou délimiteur incorrect")
	}
	return pt[:len(pt)-1]
}

// Vecteur de la RFC 8291, section 5.
func TestWebPushRFC8291Vector(t *testing.T) {
	asPriv, err := ecdh.P256().NewPrivateKey(unb64(t, "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"))
	if err != nil {
		t.Fatal(err)
	}
	uaPriv, err := ecdh.P256().NewPrivateKey(unb64(t, "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"))
	if err != nil {
		t.Fatal(err)
	}
	uaPub := unb64(t, "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4")
	if !bytes.Equal(uaPriv.PublicKey().Bytes(), uaPub) {
		t.Fatal("clé publique du vecteur incohérente")
	}
	auth := unb64(t, "BTBZMqHH6r4Tts7J_aSIgg")
	salt := unb64(t, "DGv6ra1nlYgDCS1FRnbzlw")
	plain := []byte("When I grow up, I want to be a watermelon")
	body, err := encryptWebPush(uaPub, auth, plain, asPriv, salt)
	if err != nil {
		t.Fatal(err)
	}
	if got := decryptWebPush(t, uaPriv, auth, body); !bytes.Equal(got, plain) {
		t.Fatalf("aller-retour : %q", got)
	}
	want := "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"
	if got := base64.RawURLEncoding.EncodeToString(body); got != want {
		t.Fatalf("vecteur RFC 8291 :\n obtenu  %s\n attendu %s", got, want)
	}
}

func TestPushFlow(t *testing.T) {
	type got struct {
		hdr  http.Header
		body []byte
	}
	ch := make(chan got, 4)
	svc := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		ch <- got{r.Header.Clone(), b}
		w.WriteHeader(http.StatusCreated)
	}))
	defer svc.Close()

	e := newEnv(t, nil)
	vapidPath := filepath.Join(t.TempDir(), "vapid.pem")
	p, err := NewPusher(vapidPath, "mailto:test@example.org", true, false)
	if err != nil {
		t.Fatal(err)
	}
	p.client.Transport.(*http.Transport).TLSClientConfig = &tls.Config{InsecureSkipVerify: true}
	e.srv.SetPusher(p)

	// Clé VAPID publique : 65 octets, stable au rechargement.
	rec := e.do(httptest.NewRequest("GET", "/v1/push/vapid", nil))
	wantCode(t, rec, 200)
	if !bytes.Equal(rec.Body.Bytes(), p.PublicKey()) || len(p.PublicKey()) != 65 {
		t.Fatal("clé VAPID incorrecte")
	}

	b := e.newBox()
	ua, _ := ecdh.P256().GenerateKey(rand.Reader)
	auth := randBytes(16)
	pushBody := func(u string) []byte {
		out := binary.BigEndian.AppendUint16(nil, uint16(len(u)))
		out = append(out, u...)
		out = append(out, ua.PublicKey().Bytes()...)
		return append(out, auth...)
	}
	setPush := func(body []byte) *httptest.ResponseRecorder {
		uri := "/v1/box/" + b64(b.id) + "/push"
		req := httptest.NewRequest("PUT", uri, bytes.NewReader(body))
		ts := e.clock.Unix()
		nonce := b64(randBytes(16))
		sum := sha256.Sum256(body)
		msg := "PUT\n" + uri + "\n" + strconv.FormatInt(ts, 10) + "\n" + nonce + "\n" + hex.EncodeToString(sum[:])
		req.Header.Set("X-Timestamp", strconv.FormatInt(ts, 10))
		req.Header.Set("X-Nonce", nonce)
		req.Header.Set("X-Signature", b64(ed25519.Sign(b.priv, []byte(msg))))
		return e.do(req)
	}

	// Non authentifié : refusé. http : refusé. Valide : accepté.
	wantCode(t, e.do(httptest.NewRequest("PUT", "/v1/box/"+b64(b.id)+"/push", bytes.NewReader(pushBody(svc.URL)))), 401)
	wantCode(t, setPush(pushBody("http://example.org/x")), 400)
	wantCode(t, setPush(pushBody(svc.URL+"/up123")), 204)

	// Un dépôt déclenche un réveil chiffré et signé.
	wantCode(t, e.deposit(b.wid, blob(1)), 201)
	var g got
	select {
	case g = <-ch:
	case <-time.After(5 * time.Second):
		t.Fatal("aucun réveil reçu")
	}
	if g.hdr.Get("Content-Encoding") != "aes128gcm" || g.hdr.Get("TTL") == "" {
		t.Fatalf("en-têtes : %v", g.hdr)
	}
	if pt := decryptWebPush(t, ua, auth, g.body); !bytes.Equal(pt, []byte{1}) {
		t.Fatalf("contenu du réveil : %x", pt)
	}
	authz := g.hdr.Get("Authorization")
	if !strings.HasPrefix(authz, "vapid t=") || !strings.Contains(authz, ", k="+base64.RawURLEncoding.EncodeToString(p.PublicKey())) {
		t.Fatalf("Authorization : %s", authz)
	}
	jwt := strings.TrimPrefix(strings.Split(authz, ", k=")[0], "vapid t=")
	parts := strings.Split(jwt, ".")
	var claims map[string]any
	_ = json.Unmarshal(unb64(t, parts[1]), &claims)
	if claims["aud"] != svc.URL || claims["sub"] != "mailto:test@example.org" {
		t.Fatalf("revendications VAPID : %v", claims)
	}
	if !verifyES256(&p.key.PublicKey, parts[0]+"."+parts[1], unb64(t, parts[2])) {
		t.Fatal("signature VAPID invalide")
	}

	// Regroupement : un second dépôt immédiat ne réveille pas une deuxième fois.
	wantCode(t, e.deposit(b.wid, blob(2)), 201)
	select {
	case <-ch:
		t.Fatal("réveil non regroupé")
	case <-time.After(300 * time.Millisecond):
	}

	// Suppression du point de notification.
	uri := "/v1/box/" + b64(b.id) + "/push"
	wantCode(t, e.do(e.signed(b, "DELETE", uri)), 204)
	p.now = func() time.Time { return time.Now().Add(time.Minute) }
	wantCode(t, e.deposit(b.wid, blob(3)), 201)
	select {
	case <-ch:
		t.Fatal("réveil après suppression")
	case <-time.After(300 * time.Millisecond):
	}

	// La clé VAPID est conservée d'un démarrage à l'autre.
	p2, err := NewPusher(vapidPath, "x", true, false)
	if err != nil || !bytes.Equal(p2.PublicKey(), p.PublicKey()) {
		t.Fatal("clé VAPID non conservée")
	}
}

func TestPushSSRFGuard(t *testing.T) {
	for _, s := range []string{"127.0.0.1", "10.1.2.3", "192.168.1.252", "172.16.0.1", "169.254.1.1", "100.64.0.1", "::1", "fd00::1", "0.0.0.0"} {
		if !forbiddenIP(net.ParseIP(s)) {
			t.Fatalf("%s devrait être interdit", s)
		}
	}
	for _, s := range []string{"1.1.1.1", "2606:4700::1111"} {
		if forbiddenIP(net.ParseIP(s)) {
			t.Fatalf("%s devrait être autorisé", s)
		}
	}
	p, err := NewPusher(filepath.Join(t.TempDir(), "v.pem"), "x", false, false)
	if err != nil {
		t.Fatal(err)
	}
	svc := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(201) }))
	defer svc.Close()
	ua, _ := ecdh.P256().GenerateKey(rand.Reader)
	err = p.Send(context.Background(), PushTarget{URL: svc.URL, P256dh: ua.PublicKey().Bytes(), Auth: randBytes(16)})
	if err == nil || !strings.Contains(err.Error(), "interdite") {
		t.Fatalf("envoi vers 127.0.0.1 non bloqué : %v", err)
	}
}
