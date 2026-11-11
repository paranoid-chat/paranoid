package main

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"io"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"
)

// Config regroupe les paramètres du protocole (voir PROTOCOL.md §1 et §5).
type Config struct {
	BlobSize          int
	Quota             int
	TTL               time.Duration
	BoxExpiry         time.Duration
	Tolerance         time.Duration
	CreatesPerHour    int
	DepositsPerMinute int
	MaxPage           int
	MaxDB             int64 // octets occupés dans la base au maximum (0 = sans limite)
	MinFree           int64 // octets à laisser libres sur le disque (0 = sans limite)
}

func DefaultConfig() Config {
	return Config{
		BlobSize:          2048,
		Quota:             100,
		TTL:               7 * 24 * time.Hour,
		BoxExpiry:         90 * 24 * time.Hour,
		Tolerance:         60 * time.Second,
		CreatesPerHour:    10,
		DepositsPerMinute: 60,
		MaxPage:           50,
		MaxDB:             1 << 30, // 1 Gio
		MinFree:           2 << 30, // 2 Gio
	}
}

type Server struct {
	cfg        Config
	store      *Store
	now        func() time.Time
	nonces     *nonceCache
	createLim  *limiter
	depositLim *limiter
	cap        *capacity
	mux        *http.ServeMux
}

func NewServer(cfg Config, store *Store) *Server {
	s := &Server{
		cfg:        cfg,
		store:      store,
		now:        time.Now,
		nonces:     newNonceCache(),
		createLim:  newLimiter(cfg.CreatesPerHour, time.Hour),
		depositLim: newLimiter(cfg.DepositsPerMinute, time.Minute),
		cap:        newCapacity(cfg.MaxDB, cfg.MinFree, store.UsedBytes, store.FreeBytes),
		mux:        http.NewServeMux(),
	}
	s.mux.HandleFunc("POST /v1/box", s.handleCreate)
	s.mux.HandleFunc("PUT /v1/box/{write_id}/msg", s.handleDeposit)
	s.mux.HandleFunc("GET /v1/box/{box_id}/msgs", s.handleFetch)
	s.mux.HandleFunc("DELETE /v1/box/{box_id}/msgs", s.handleAck)
	s.mux.HandleFunc("DELETE /v1/box/{box_id}", s.handleDeleteBox)
	return s
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	s.mux.ServeHTTP(w, r)
}

// Purge applique le TTL, l'expiration des boîtes et le nettoyage des caches mémoire.
func (s *Server) Purge() error {
	now := s.now()
	s.nonces.gc(now)
	s.createLim.gc(now)
	s.depositLim.gc(now)
	return s.store.Purge(now.Unix(), int64(s.cfg.TTL.Seconds()), int64(s.cfg.BoxExpiry.Seconds()))
}

// ---------- utilitaires ----------

func b64(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

func decodeID(s string) ([]byte, bool) {
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil || len(b) != 16 {
		return nil, false
	}
	return b, true
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func readExact(w http.ResponseWriter, r *http.Request, n int) ([]byte, bool) {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, int64(n)+1))
	if err != nil || len(body) != n {
		http.Error(w, "taille de corps invalide", http.StatusBadRequest)
		return nil, false
	}
	return body, true
}

func relayFull(w http.ResponseWriter) {
	w.Header().Set("X-Relay-Full", "1")
	http.Error(w, "relais plein", http.StatusInsufficientStorage)
}

func tooMany(w http.ResponseWriter, retry time.Duration) {
	secs := int(retry.Seconds()) + 1
	w.Header().Set("Retry-After", strconv.Itoa(secs))
	http.Error(w, "trop de requêtes", http.StatusTooManyRequests)
}

// ---------- authentification (PROTOCOL.md §4) ----------

// authenticate vérifie la signature d'une requête de lecture. En cas d'échec, la réponse est déjà écrite.
func (s *Server) authenticate(w http.ResponseWriter, r *http.Request, boxID []byte) bool {
	_, ok := s.authBody(w, r, boxID)
	return ok
}

// authBody vérifie la signature et renvoie le corps de la requête (couvert par la signature).
func (s *Server) authBody(w http.ResponseWriter, r *http.Request, boxID []byte) ([]byte, bool) {
	pub, err := s.store.PubKey(boxID)
	if errors.Is(err, ErrNotFound) {
		http.Error(w, "boîte inconnue", http.StatusNotFound)
		return nil, false
	}
	if err != nil {
		http.Error(w, "erreur interne", http.StatusInternalServerError)
		return nil, false
	}
	fail := func() ([]byte, bool) {
		http.Error(w, "authentification invalide", http.StatusUnauthorized)
		return nil, false
	}

	tsStr := r.Header.Get("X-Timestamp")
	ts, err := strconv.ParseInt(tsStr, 10, 64)
	if err != nil {
		return fail()
	}
	now := s.now()
	diff := now.Unix() - ts
	if diff < 0 {
		diff = -diff
	}
	if time.Duration(diff)*time.Second > s.cfg.Tolerance {
		return fail()
	}

	nonceStr := r.Header.Get("X-Nonce")
	nonce, err := base64.RawURLEncoding.DecodeString(nonceStr)
	if err != nil || len(nonce) != 16 {
		return fail()
	}
	sig, err := base64.RawURLEncoding.DecodeString(r.Header.Get("X-Signature"))
	if err != nil || len(sig) != ed25519.SignatureSize {
		return fail()
	}

	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 1024))
	if err != nil {
		return fail()
	}
	sum := sha256.Sum256(body)
	msg := r.Method + "\n" + r.URL.RequestURI() + "\n" + tsStr + "\n" + nonceStr + "\n" + hex.EncodeToString(sum[:])
	if len(pub) != ed25519.PublicKeySize || !ed25519.Verify(ed25519.PublicKey(pub), []byte(msg), sig) {
		return fail()
	}

	// Le nonce n'est mémorisé qu'après une signature valide : un tiers ne peut pas remplir le cache.
	if !s.nonces.add(hex.EncodeToString(boxID)+":"+nonceStr, now, 2*s.cfg.Tolerance+time.Second) {
		return fail()
	}
	_ = s.store.Touch(boxID, now.Unix())
	return body, true
}

// ---------- handlers ----------

func (s *Server) handleCreate(w http.ResponseWriter, r *http.Request) {
	if !s.cap.canCreate(s.now()) {
		relayFull(w)
		return
	}
	if ok, retry := s.createLim.allow(clientIP(r), s.now()); !ok {
		tooMany(w, retry)
		return
	}
	pub, ok := readExact(w, r, ed25519.PublicKeySize)
	if !ok {
		return
	}
	boxID, writeID, err := s.store.CreateBox(pub, s.now().Unix())
	if err != nil {
		http.Error(w, "erreur interne", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.WriteHeader(http.StatusCreated)
	w.Write(append(boxID, writeID...))
}

func (s *Server) handleDeposit(w http.ResponseWriter, r *http.Request) {
	writeID, ok := decodeID(r.PathValue("write_id"))
	if !ok {
		http.Error(w, "identifiant invalide", http.StatusBadRequest)
		return
	}
	exists, err := s.store.WriteIDExists(writeID)
	if err != nil {
		http.Error(w, "erreur interne", http.StatusInternalServerError)
		return
	}
	if !exists {
		http.Error(w, "boîte inconnue", http.StatusNotFound)
		return
	}
	if ok, retry := s.depositLim.allow(hex.EncodeToString(writeID), s.now()); !ok {
		tooMany(w, retry)
		return
	}
	if !s.cap.canDeposit(s.now()) {
		relayFull(w)
		return
	}
	blob, ok := readExact(w, r, s.cfg.BlobSize)
	if !ok {
		return
	}
	seq, err := s.store.Deposit(writeID, blob, s.now().Unix(), s.cfg.Quota)
	switch {
	case errors.Is(err, ErrNotFound):
		http.Error(w, "boîte inconnue", http.StatusNotFound)
	case errors.Is(err, ErrQuota):
		http.Error(w, "quota atteint", http.StatusInsufficientStorage)
	case err != nil:
		http.Error(w, "erreur interne", http.StatusInternalServerError)
	default:
		var out [8]byte
		binary.BigEndian.PutUint64(out[:], seq)
		w.Header().Set("Content-Type", "application/octet-stream")
		w.WriteHeader(http.StatusCreated)
		w.Write(out[:])
	}
}

func (s *Server) handleFetch(w http.ResponseWriter, r *http.Request) {
	boxID, ok := decodeID(r.PathValue("box_id"))
	if !ok {
		http.Error(w, "identifiant invalide", http.StatusBadRequest)
		return
	}
	if !s.authenticate(w, r, boxID) {
		return
	}
	q := r.URL.Query()
	var after uint64
	if v := q.Get("after"); v != "" {
		n, err := strconv.ParseUint(v, 10, 64)
		if err != nil {
			http.Error(w, "paramètre after invalide", http.StatusBadRequest)
			return
		}
		after = n
	}
	limit := s.cfg.MaxPage
	if v := q.Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > s.cfg.MaxPage {
			http.Error(w, "paramètre limit invalide", http.StatusBadRequest)
			return
		}
		limit = n
	}
	msgs, more, err := s.store.Fetch(boxID, after, limit)
	if err != nil {
		http.Error(w, "erreur interne", http.StatusInternalServerError)
		return
	}
	out := make([]byte, 0, len(msgs)*(16+s.cfg.BlobSize))
	for _, m := range msgs {
		var head [16]byte
		binary.BigEndian.PutUint64(head[:8], m.Seq)
		binary.BigEndian.PutUint64(head[8:], uint64(m.TS))
		out = append(out, head[:]...)
		out = append(out, m.Payload...)
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	if more {
		w.Header().Set("X-More", "1")
	}
	w.WriteHeader(http.StatusOK)
	w.Write(out)
}

func (s *Server) handleAck(w http.ResponseWriter, r *http.Request) {
	boxID, ok := decodeID(r.PathValue("box_id"))
	if !ok {
		http.Error(w, "identifiant invalide", http.StatusBadRequest)
		return
	}
	if !s.authenticate(w, r, boxID) {
		return
	}
	upto, err := strconv.ParseUint(r.URL.Query().Get("upto"), 10, 64)
	if err != nil {
		http.Error(w, "paramètre upto invalide", http.StatusBadRequest)
		return
	}
	if err := s.store.Ack(boxID, upto); err != nil {
		http.Error(w, "erreur interne", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleDeleteBox(w http.ResponseWriter, r *http.Request) {
	boxID, ok := decodeID(r.PathValue("box_id"))
	if !ok {
		http.Error(w, "identifiant invalide", http.StatusBadRequest)
		return
	}
	if !s.authenticate(w, r, boxID) {
		return
	}
	if err := s.store.DeleteBox(boxID); err != nil {
		http.Error(w, "erreur interne", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---------- anti-rejeu et limitation de débit (en mémoire uniquement) ----------

type nonceCache struct {
	mu sync.Mutex
	m  map[string]time.Time
}

func newNonceCache() *nonceCache { return &nonceCache{m: make(map[string]time.Time)} }

// add renvoie false si le nonce est déjà présent et non expiré.
func (c *nonceCache) add(key string, now time.Time, ttl time.Duration) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	if exp, ok := c.m[key]; ok && now.Before(exp) {
		return false
	}
	c.m[key] = now.Add(ttl)
	return true
}

func (c *nonceCache) gc(now time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for k, exp := range c.m {
		if !now.Before(exp) {
			delete(c.m, k)
		}
	}
}

type bucket struct {
	tokens float64
	last   time.Time
}

// limiter est un seau à jetons par clé : capacité = events, recharge = events par période.
type limiter struct {
	mu     sync.Mutex
	cap    float64
	perSec float64
	m      map[string]*bucket
}

func newLimiter(events int, per time.Duration) *limiter {
	return &limiter{
		cap:    float64(events),
		perSec: float64(events) / per.Seconds(),
		m:      make(map[string]*bucket),
	}
}

func (l *limiter) refill(b *bucket, now time.Time) {
	if el := now.Sub(b.last).Seconds(); el > 0 {
		b.tokens += el * l.perSec
		if b.tokens > l.cap {
			b.tokens = l.cap
		}
	}
	b.last = now
}

func (l *limiter) allow(key string, now time.Time) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	b, ok := l.m[key]
	if !ok {
		b = &bucket{tokens: l.cap, last: now}
		l.m[key] = b
	}
	l.refill(b, now)
	if b.tokens >= 1 {
		b.tokens--
		return true, 0
	}
	return false, time.Duration((1 - b.tokens) / l.perSec * float64(time.Second))
}

func (l *limiter) gc(now time.Time) {
	l.mu.Lock()
	defer l.mu.Unlock()
	for k, b := range l.m {
		l.refill(b, now)
		if b.tokens >= l.cap {
			delete(l.m, k)
		}
	}
}
