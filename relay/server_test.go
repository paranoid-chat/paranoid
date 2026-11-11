package main

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"testing"
	"time"
)

type env struct {
	t     *testing.T
	srv   *Server
	clock time.Time
}

func newEnv(t *testing.T, mod func(*Config)) *env {
	t.Helper()
	cfg := DefaultConfig()
	cfg.CreatesPerHour = 1000
	cfg.DepositsPerMinute = 1000
	if mod != nil {
		mod(&cfg)
	}
	st, err := OpenStore(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	e := &env{t: t, clock: time.Unix(1_800_000_000, 0)}
	e.srv = NewServer(cfg, st)
	e.srv.now = func() time.Time { return e.clock }
	return e
}

func (e *env) do(req *http.Request) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	e.srv.ServeHTTP(rec, req)
	return rec
}

type box struct {
	id, wid []byte
	priv    ed25519.PrivateKey
}

func (e *env) newBox() box {
	e.t.Helper()
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		e.t.Fatal(err)
	}
	rec := e.do(httptest.NewRequest("POST", "/v1/box", bytes.NewReader(pub)))
	if rec.Code != http.StatusCreated {
		e.t.Fatalf("création: code %d", rec.Code)
	}
	body := rec.Body.Bytes()
	if len(body) != 32 {
		e.t.Fatalf("création: %d octets au lieu de 32", len(body))
	}
	return box{id: body[:16], wid: body[16:], priv: priv}
}

func blob(fill byte) []byte { return bytes.Repeat([]byte{fill}, 2048) }

func (e *env) deposit(wid, payload []byte) *httptest.ResponseRecorder {
	return e.do(httptest.NewRequest("PUT", "/v1/box/"+b64(wid)+"/msg", bytes.NewReader(payload)))
}

func randBytes(n int) []byte {
	b := make([]byte, n)
	rand.Read(b)
	return b
}

func signedAt(priv ed25519.PrivateKey, method, uri string, ts int64, nonce []byte) *http.Request {
	req := httptest.NewRequest(method, uri, nil)
	tsStr := strconv.FormatInt(ts, 10)
	nStr := b64(nonce)
	sum := sha256.Sum256(nil)
	msg := method + "\n" + uri + "\n" + tsStr + "\n" + nStr + "\n" + hex.EncodeToString(sum[:])
	req.Header.Set("X-Timestamp", tsStr)
	req.Header.Set("X-Nonce", nStr)
	req.Header.Set("X-Signature", b64(ed25519.Sign(priv, []byte(msg))))
	return req
}

func (e *env) signed(b box, method, uri string) *http.Request {
	return signedAt(b.priv, method, uri, e.clock.Unix(), randBytes(16))
}

func (e *env) fetch(b box, query string) *httptest.ResponseRecorder {
	return e.do(e.signed(b, "GET", "/v1/box/"+b64(b.id)+"/msgs"+query))
}

func wantCode(t *testing.T, rec *httptest.ResponseRecorder, code int) {
	t.Helper()
	if rec.Code != code {
		t.Fatalf("code %d, attendu %d (%s)", rec.Code, code, rec.Body.String())
	}
}

func TestFullCycle(t *testing.T) {
	e := newEnv(t, nil)
	b := e.newBox()

	for i, fill := range []byte{1, 2} {
		rec := e.deposit(b.wid, blob(fill))
		wantCode(t, rec, 201)
		if seq := binary.BigEndian.Uint64(rec.Body.Bytes()); seq != uint64(i+1) {
			t.Fatalf("seq %d, attendu %d", seq, i+1)
		}
	}

	rec := e.fetch(b, "")
	wantCode(t, rec, 200)
	body := rec.Body.Bytes()
	if len(body) != 2*(16+2048) {
		t.Fatalf("longueur %d", len(body))
	}
	if rec.Header().Get("X-More") != "" {
		t.Fatal("X-More ne devrait pas être présent")
	}
	for i, fill := range []byte{1, 2} {
		rcd := body[i*2064 : (i+1)*2064]
		if binary.BigEndian.Uint64(rcd[:8]) != uint64(i+1) {
			t.Fatalf("seq enregistrement %d incorrecte", i)
		}
		if int64(binary.BigEndian.Uint64(rcd[8:16])) != e.clock.Unix() {
			t.Fatalf("horodatage enregistrement %d incorrect", i)
		}
		if !bytes.Equal(rcd[16:], blob(fill)) {
			t.Fatalf("contenu enregistrement %d incorrect", i)
		}
	}

	wantCode(t, e.do(e.signed(b, "DELETE", "/v1/box/"+b64(b.id)+"/msgs?upto=1")), 204)
	rec = e.fetch(b, "?after=0")
	wantCode(t, rec, 200)
	if len(rec.Body.Bytes()) != 2064 || binary.BigEndian.Uint64(rec.Body.Bytes()[:8]) != 2 {
		t.Fatal("après acquittement, seul le message 2 doit rester")
	}

	wantCode(t, e.do(e.signed(b, "DELETE", "/v1/box/"+b64(b.id))), 204)
	wantCode(t, e.fetch(b, ""), 404)
	wantCode(t, e.deposit(b.wid, blob(3)), 404)
}

func TestSizes(t *testing.T) {
	e := newEnv(t, nil)
	b := e.newBox()
	wantCode(t, e.deposit(b.wid, make([]byte, 2047)), 400)
	wantCode(t, e.deposit(b.wid, make([]byte, 2049)), 400)
	wantCode(t, e.do(httptest.NewRequest("POST", "/v1/box", bytes.NewReader(make([]byte, 31)))), 400)
	wantCode(t, e.do(httptest.NewRequest("POST", "/v1/box", bytes.NewReader(make([]byte, 33)))), 400)
}

func TestUnknownAndMalformedIDs(t *testing.T) {
	e := newEnv(t, nil)
	wantCode(t, e.deposit(randBytes(16), blob(1)), 404)
	wantCode(t, e.do(httptest.NewRequest("PUT", "/v1/box/pas-un-id/msg", bytes.NewReader(blob(1)))), 400)
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	unknown := box{id: randBytes(16), priv: priv}
	wantCode(t, e.fetch(unknown, ""), 404)
}

func TestQuota(t *testing.T) {
	e := newEnv(t, func(c *Config) { c.Quota = 3 })
	b := e.newBox()
	for i := 0; i < 3; i++ {
		wantCode(t, e.deposit(b.wid, blob(byte(i))), 201)
	}
	wantCode(t, e.deposit(b.wid, blob(9)), 507)
	wantCode(t, e.do(e.signed(b, "DELETE", "/v1/box/"+b64(b.id)+"/msgs?upto=1")), 204)
	rec := e.deposit(b.wid, blob(9))
	wantCode(t, rec, 201)
	if seq := binary.BigEndian.Uint64(rec.Body.Bytes()); seq != 4 {
		t.Fatalf("seq %d, attendu 4 (les séquences ne sont jamais réutilisées)", seq)
	}
}

func TestPagination(t *testing.T) {
	e := newEnv(t, nil)
	b := e.newBox()
	for i := 0; i < 5; i++ {
		wantCode(t, e.deposit(b.wid, blob(byte(i))), 201)
	}
	rec := e.fetch(b, "?after=0&limit=2")
	wantCode(t, rec, 200)
	if len(rec.Body.Bytes()) != 2*2064 || rec.Header().Get("X-More") != "1" {
		t.Fatal("page 1 incorrecte")
	}
	rec = e.fetch(b, "?after=2&limit=2")
	if len(rec.Body.Bytes()) != 2*2064 || rec.Header().Get("X-More") != "1" {
		t.Fatal("page 2 incorrecte")
	}
	rec = e.fetch(b, "?after=4&limit=2")
	if len(rec.Body.Bytes()) != 2064 || rec.Header().Get("X-More") != "" {
		t.Fatal("page 3 incorrecte")
	}
	wantCode(t, e.fetch(b, "?limit=0"), 400)
	wantCode(t, e.fetch(b, "?limit=51"), 400)
	wantCode(t, e.fetch(b, "?after=abc"), 400)
}

func TestAuthentication(t *testing.T) {
	e := newEnv(t, nil)
	b := e.newBox()
	uri := "/v1/box/" + b64(b.id) + "/msgs?after=0"

	// Mauvaise clé.
	_, other, _ := ed25519.GenerateKey(rand.Reader)
	wantCode(t, e.do(signedAt(other, "GET", uri, e.clock.Unix(), randBytes(16))), 401)

	// Sans en-têtes.
	wantCode(t, e.do(httptest.NewRequest("GET", uri, nil)), 401)

	// Horodatage hors tolérance (passé et futur), puis dans la tolérance.
	wantCode(t, e.do(signedAt(b.priv, "GET", uri, e.clock.Unix()-120, randBytes(16))), 401)
	wantCode(t, e.do(signedAt(b.priv, "GET", uri, e.clock.Unix()+120, randBytes(16))), 401)
	wantCode(t, e.do(signedAt(b.priv, "GET", uri, e.clock.Unix()-30, randBytes(16))), 200)

	// Rejeu du même nonce.
	nonce := randBytes(16)
	wantCode(t, e.do(signedAt(b.priv, "GET", uri, e.clock.Unix(), nonce)), 200)
	wantCode(t, e.do(signedAt(b.priv, "GET", uri, e.clock.Unix(), nonce)), 401)

	// Requête modifiée après signature.
	req := signedAt(b.priv, "GET", uri, e.clock.Unix(), randBytes(16))
	req.URL.RawQuery = "after=1"
	wantCode(t, e.do(req), 401)

	// Une signature de lecture ne vaut pas pour une autre méthode.
	req = signedAt(b.priv, "GET", "/v1/box/"+b64(b.id), e.clock.Unix(), randBytes(16))
	req.Method = "DELETE"
	wantCode(t, e.do(req), 401)
}

func TestRateLimits(t *testing.T) {
	e := newEnv(t, func(c *Config) {
		c.CreatesPerHour = 2
		c.DepositsPerMinute = 2
	})
	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	create := func() *httptest.ResponseRecorder {
		return e.do(httptest.NewRequest("POST", "/v1/box", bytes.NewReader(pub)))
	}
	wantCode(t, create(), 201)
	rec := create()
	wantCode(t, rec, 201)
	boxBody := rec.Body.Bytes()
	wid := boxBody[16:]
	rec = create()
	wantCode(t, rec, 429)
	if rec.Header().Get("Retry-After") == "" {
		t.Fatal("Retry-After manquant")
	}
	e.clock = e.clock.Add(time.Hour)
	wantCode(t, create(), 201)

	wantCode(t, e.deposit(wid, blob(1)), 201)
	wantCode(t, e.deposit(wid, blob(1)), 201)
	wantCode(t, e.deposit(wid, blob(1)), 429)
	e.clock = e.clock.Add(time.Minute)
	wantCode(t, e.deposit(wid, blob(1)), 201)
}

func TestPurge(t *testing.T) {
	e := newEnv(t, nil)
	b := e.newBox()
	wantCode(t, e.deposit(b.wid, blob(1)), 201)

	// Après le TTL (7 jours) : le message disparaît, la boîte reste.
	e.clock = e.clock.Add(8 * 24 * time.Hour)
	if err := e.srv.Purge(); err != nil {
		t.Fatal(err)
	}
	rec := e.fetch(b, "")
	wantCode(t, rec, 200)
	if len(rec.Body.Bytes()) != 0 {
		t.Fatal("le message expiré devrait être supprimé")
	}
	wantCode(t, e.deposit(b.wid, blob(2)), 201)

	// Après 90 jours sans relève : la boîte disparaît.
	e.clock = e.clock.Add(91 * 24 * time.Hour)
	if err := e.srv.Purge(); err != nil {
		t.Fatal(err)
	}
	wantCode(t, e.fetch(b, ""), 404)
	wantCode(t, e.deposit(b.wid, blob(3)), 404)
}

func TestBoxExpiryResetByRead(t *testing.T) {
	e := newEnv(t, nil)
	b := e.newBox()
	for i := 0; i < 5; i++ {
		e.clock = e.clock.Add(60 * 24 * time.Hour)
		wantCode(t, e.fetch(b, ""), 200) // chaque relève repousse l'expiration
		if err := e.srv.Purge(); err != nil {
			t.Fatal(err)
		}
	}
	wantCode(t, e.fetch(b, ""), 200)
}

func TestRelayFull(t *testing.T) {
	e := newEnv(t, func(c *Config) { c.MaxDB = 1000; c.MinFree = 500 })
	used, free := int64(0), int64(10_000)
	e.srv.cap.used = func() (int64, error) { return used, nil }
	e.srv.cap.free = func() (int64, error) { return free, nil }
	e.srv.cap.every = 0 // mesure à chaque requête

	writeID := e.newBox().wid
	create := func() *httptest.ResponseRecorder {
		return e.do(httptest.NewRequest("POST", "/v1/box", bytes.NewReader(randBytes(32))))
	}
	if code := e.deposit(writeID, blob(1)).Code; code != http.StatusCreated {
		t.Fatalf("dépôt sous le plafond : %d", code)
	}
	// 90 % : plus de nouvelles boîtes, mais les dépôts passent encore.
	used = 900
	if rec := create(); rec.Code != http.StatusInsufficientStorage || rec.Header().Get("X-Relay-Full") != "1" {
		t.Fatalf("création à 90 %% : %d", rec.Code)
	}
	if code := e.deposit(writeID, blob(1)).Code; code != http.StatusCreated {
		t.Fatalf("dépôt à 90 %% : %d", code)
	}
	// 100 % : plus aucun dépôt.
	used = 1000
	if rec := e.deposit(writeID, blob(1)); rec.Code != http.StatusInsufficientStorage || rec.Header().Get("X-Relay-Full") != "1" {
		t.Fatalf("dépôt à 100 %% : %d", rec.Code)
	}
	// Disque presque plein, base sous le plafond : refus aussi.
	used, free = 0, 100
	if code := e.deposit(writeID, blob(1)).Code; code != http.StatusInsufficientStorage {
		t.Fatalf("dépôt disque plein : %d", code)
	}
	if code := create().Code; code != http.StatusInsufficientStorage {
		t.Fatalf("création disque plein : %d", code)
	}
	free = 10_000
	if code := e.deposit(writeID, blob(1)).Code; code != http.StatusCreated {
		t.Fatalf("dépôt après libération : %d", code)
	}
}

func TestUsedBytes(t *testing.T) {
	st, err := OpenStore(filepath.Join(t.TempDir(), "u.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	if n, err := st.UsedBytes(); err != nil || n <= 0 {
		t.Fatalf("UsedBytes = %d, %v", n, err)
	}
	if n, err := st.FreeBytes(); err != nil || n <= 0 {
		t.Fatalf("FreeBytes = %d, %v", n, err)
	}
}

func TestParseSize(t *testing.T) {
	for in, want := range map[string]int64{"0": 0, "512": 512, "1K": 1024, "512M": 512 << 20, "1g": 1 << 30, "2T": 2 << 40} {
		if got, err := parseSize(in); err != nil || got != want {
			t.Fatalf("parseSize(%q) = %d, %v", in, got, err)
		}
	}
	for _, bad := range []string{"", "G", "-1", "1.5G", "abc"} {
		if _, err := parseSize(bad); err == nil {
			t.Fatalf("parseSize(%q) accepté", bad)
		}
	}
}
