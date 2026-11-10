package main

// Double Ratchet (format de message v2), d'après la spécification de Signal :
// https://signal.org/docs/specifications/doubleratchet/
//
// Adaptations à notre modèle (voir MESSAGES.md) :
//   - Pas de X3DH : le secret initial SK vient des clés X25519 statiques échangées par QR.
//   - Rôles fixes : l'« initiateur » est celui dont la clé d'identité Ed25519 est la plus petite
//     (ordre des octets). Il peut écrire tout de suite, comme Alice dans la spécification.
//   - Le « répondeur » ne peut pas utiliser le ratchet avant d'avoir reçu un message. S'il écrit
//     en premier, il utilise une chaîne de démarrage (boot) à sens unique, dérivée de SK et d'une
//     clé éphémère, jusqu'à la réception du premier message de l'initiateur.
//   - Chiffrement : AES-256-GCM, clé et nonce dérivés de la clé de message (usage unique).
//   - L'en-tête est en clair (pas de chiffrement d'en-tête dans cette version).

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/hkdf"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"errors"
)

const (
	drVersion   = 2
	drTypeRatch = 0
	drTypeBoot  = 1

	drHeaderLen = 1 + 1 + 32 + 4 + 4 // version, type, clé DH, PN, N
	drInnerLen  = BlobSize - drHeaderLen - tagLen
	MaxTextV2   = drInnerLen - innerHead // 1963 octets

	maxSkip     = 1000 // clés sautées au maximum pour un seul message
	maxSkipped  = 2000 // clés sautées conservées au total
	drInfoSK    = "relay-dr-v1/sk"
	drInfoRK    = "relay-dr-v1/rk"
	drInfoBoot  = "relay-dr-v1/boot"
	drInfoMsg   = "relay-dr-v1/msg"
	drADContext = "relay-dr-v1/ad"
)

var (
	ErrDRReplay  = errors.New("message déjà lu, trop ancien ou non authentique")
	ErrDRSkip    = errors.New("trop de messages manquants")
	ErrDRState   = errors.New("état de session incohérent")
	ErrDRVersion = errors.New("version de message non prise en charge")
)

type chainState struct {
	DH []byte `json:"dh"`
	CK []byte `json:"ck"`
	N  uint32 `json:"n"`
}

type skippedKey struct {
	DH []byte `json:"dh"`
	N  uint32 `json:"n"`
	MK []byte `json:"mk"`
}

// Session est l'état du ratchet pour un contact. Il évolue à chaque message envoyé ou reçu.
type Session struct {
	Initiator bool         `json:"initiator"`
	RK        []byte       `json:"rk"`
	DHsPriv   []byte       `json:"dhs"`
	DHr       []byte       `json:"dhr,omitempty"`
	CKs       []byte       `json:"cks,omitempty"`
	CKr       []byte       `json:"ckr,omitempty"`
	Ns        uint32       `json:"ns"`
	Nr        uint32       `json:"nr"`
	PN        uint32       `json:"pn"`
	Skipped   []skippedKey `json:"skipped,omitempty"`
	BootSend  *chainState  `json:"boot_send,omitempty"` // répondeur, avant sa première réception
	BootRecv  *chainState  `json:"boot_recv,omitempty"` // initiateur, chaîne de démarrage du répondeur
}

// drEnv regroupe les éléments dérivés des clés statiques, recalculés à chaque usage (jamais stockés).
type drEnv struct {
	SK      []byte
	AD      []byte
	myX     *ecdh.PrivateKey
	theirXK *ecdh.PublicKey
	init    bool
}

func newDREnv(myIK ed25519.PublicKey, myX *ecdh.PrivateKey, theirIK, theirXKb []byte) (*drEnv, error) {
	theirXK, err := ecdh.X25519().NewPublicKey(theirXKb)
	if err != nil {
		return nil, err
	}
	cmp := bytes.Compare(myIK, theirIK)
	if cmp == 0 {
		return nil, ErrDRState
	}
	init := cmp < 0
	ikLo, ikHi := []byte(myIK), theirIK
	xkLo, xkHi := myX.PublicKey().Bytes(), theirXKb
	if !init {
		ikLo, ikHi, xkLo, xkHi = ikHi, ikLo, xkHi, xkLo
	}
	dh, err := myX.ECDH(theirXK)
	if err != nil {
		return nil, err
	}
	salt := concat(ikLo, ikHi, xkLo, xkHi)
	sk, err := hkdf.Key(sha256.New, dh, salt, drInfoSK, 32)
	if err != nil {
		return nil, err
	}
	return &drEnv{SK: sk, AD: concat([]byte(drADContext), ikLo, ikHi), myX: myX, theirXK: theirXK, init: init}, nil
}

func concat(parts ...[]byte) []byte {
	var out []byte
	for _, p := range parts {
		out = append(out, p...)
	}
	return out
}

// ---------- fonctions de dérivation ----------

func kdfRK(rk, dhOut []byte) (newRK, ck []byte, err error) {
	out, err := hkdf.Key(sha256.New, dhOut, rk, drInfoRK, 64)
	if err != nil {
		return nil, nil, err
	}
	return out[:32], out[32:], nil
}

func kdfCK(ck []byte) (newCK, mk []byte) {
	m := hmac.New(sha256.New, ck)
	m.Write([]byte{0x01})
	mk = m.Sum(nil)
	m = hmac.New(sha256.New, ck)
	m.Write([]byte{0x02})
	return m.Sum(nil), mk
}

func bootCK(sk, dhOut []byte) ([]byte, error) {
	return hkdf.Key(sha256.New, dhOut, sk, drInfoBoot, 32)
}

func msgAEAD(mk []byte) (cipher.AEAD, []byte, error) {
	out, err := hkdf.Key(sha256.New, mk, nil, drInfoMsg, 32+nonceLen)
	if err != nil {
		return nil, nil, err
	}
	block, err := aes.NewCipher(out[:32])
	if err != nil {
		return nil, nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, nil, err
	}
	return gcm, out[32:], nil
}

func dhPub(priv []byte) ([]byte, error) {
	k, err := ecdh.X25519().NewPrivateKey(priv)
	if err != nil {
		return nil, err
	}
	return k.PublicKey().Bytes(), nil
}

func dh(priv, pub []byte) ([]byte, error) {
	k, err := ecdh.X25519().NewPrivateKey(priv)
	if err != nil {
		return nil, err
	}
	p, err := ecdh.X25519().NewPublicKey(pub)
	if err != nil {
		return nil, err
	}
	return k.ECDH(p)
}

// ---------- initialisation ----------

// NewSession crée l'état initial à partir des clés statiques (le résultat du double scan).
func NewSession(e *drEnv) (*Session, error) {
	if !e.init {
		// Répondeur : sa paire DH initiale est sa clé statique, la chaîne d'envoi n'existe pas encore.
		return &Session{Initiator: false, RK: append([]byte(nil), e.SK...), DHsPriv: e.myX.Bytes()}, nil
	}
	k, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	out, err := k.ECDH(e.theirXK)
	if err != nil {
		return nil, err
	}
	rk, cks, err := kdfRK(e.SK, out)
	if err != nil {
		return nil, err
	}
	return &Session{Initiator: true, RK: rk, DHsPriv: k.Bytes(), DHr: e.theirXK.Bytes(), CKs: cks}, nil
}

func (s *Session) clone() *Session {
	data, err := json.Marshal(s)
	if err != nil {
		panic(err)
	}
	var c Session
	if err := json.Unmarshal(data, &c); err != nil {
		panic(err)
	}
	return &c
}

// ---------- en-tête ----------

type drHeader struct {
	Type byte
	DH   []byte
	PN   uint32
	N    uint32
}

func (h drHeader) bytes() []byte {
	b := make([]byte, drHeaderLen)
	b[0] = drVersion
	b[1] = h.Type
	copy(b[2:34], h.DH)
	binary.BigEndian.PutUint32(b[34:], h.PN)
	binary.BigEndian.PutUint32(b[38:], h.N)
	return b
}

func parseDRHeader(blob []byte) (drHeader, error) {
	var h drHeader
	if len(blob) != BlobSize {
		return h, ErrDecrypt
	}
	if blob[0] != drVersion {
		return h, ErrDRVersion
	}
	h.Type = blob[1]
	if h.Type != drTypeRatch && h.Type != drTypeBoot {
		return h, ErrDecrypt
	}
	h.DH = append([]byte(nil), blob[2:34]...)
	h.PN = binary.BigEndian.Uint32(blob[34:])
	h.N = binary.BigEndian.Uint32(blob[38:])
	return h, nil
}

// ---------- chiffrement ----------

// Encrypt chiffre inner (drInnerLen octets) et fait avancer la chaîne d'envoi.
// L'appelant doit sauvegarder la session AVANT de déposer le message, pour ne jamais réutiliser une clé.
func (s *Session) Encrypt(e *drEnv, inner []byte) ([]byte, error) {
	if len(inner) != drInnerLen {
		return nil, errors.New("taille de contenu incorrecte")
	}
	var h drHeader
	var mk []byte
	switch {
	case s.CKs != nil:
		pub, err := dhPub(s.DHsPriv)
		if err != nil {
			return nil, err
		}
		s.CKs, mk = kdfCK(s.CKs)
		h = drHeader{Type: drTypeRatch, DH: pub, PN: s.PN, N: s.Ns}
		s.Ns++
	case !s.Initiator:
		if s.BootSend == nil {
			eph, err := ecdh.X25519().GenerateKey(rand.Reader)
			if err != nil {
				return nil, err
			}
			out, err := eph.ECDH(e.theirXK)
			if err != nil {
				return nil, err
			}
			ck, err := bootCK(e.SK, out)
			if err != nil {
				return nil, err
			}
			// La clé privée éphémère n'est pas conservée.
			s.BootSend = &chainState{DH: eph.PublicKey().Bytes(), CK: ck}
		}
		s.BootSend.CK, mk = kdfCK(s.BootSend.CK)
		h = drHeader{Type: drTypeBoot, DH: s.BootSend.DH, N: s.BootSend.N}
		s.BootSend.N++
	default:
		return nil, ErrDRState
	}
	gcm, nonce, err := msgAEAD(mk)
	if err != nil {
		return nil, err
	}
	hb := h.bytes()
	blob := gcm.Seal(hb, nonce, inner, concat(e.AD, hb))
	if len(blob) != BlobSize {
		return nil, errors.New("taille de bloc inattendue")
	}
	return blob, nil
}

// Decrypt déchiffre un bloc v2. L'état n'est modifié que si le déchiffrement réussit :
// un bloc forgé ou altéré ne peut pas désynchroniser la session.
func (s *Session) Decrypt(e *drEnv, blob []byte) ([]byte, error) {
	h, err := parseDRHeader(blob)
	if err != nil {
		return nil, err
	}
	c := s.clone()
	mk, err := c.messageKey(e, h)
	if err != nil {
		return nil, err
	}
	gcm, nonce, err := msgAEAD(mk)
	if err != nil {
		return nil, err
	}
	inner, err := gcm.Open(nil, nonce, blob[drHeaderLen:], concat(e.AD, blob[:drHeaderLen]))
	if err != nil || len(inner) != drInnerLen {
		return nil, ErrDecrypt
	}
	*s = *c
	return inner, nil
}

func (c *Session) takeSkipped(dhKey []byte, n uint32) []byte {
	for i, k := range c.Skipped {
		if k.N == n && bytes.Equal(k.DH, dhKey) {
			c.Skipped = append(c.Skipped[:i:i], c.Skipped[i+1:]...)
			return k.MK
		}
	}
	return nil
}

// skipTo dérive et met de côté les clés des messages [ch.N, until) d'une chaîne de réception.
func (c *Session) skipTo(dhKey []byte, ck *[]byte, n *uint32, until uint32) error {
	if until < *n {
		return ErrDRReplay
	}
	if until-*n > maxSkip {
		return ErrDRSkip
	}
	for *n < until {
		var mk []byte
		*ck, mk = kdfCK(*ck)
		c.Skipped = append(c.Skipped, skippedKey{DH: append([]byte(nil), dhKey...), N: *n, MK: mk})
		*n++
	}
	if len(c.Skipped) > maxSkipped {
		c.Skipped = c.Skipped[len(c.Skipped)-maxSkipped:]
	}
	return nil
}

func (c *Session) messageKey(e *drEnv, h drHeader) ([]byte, error) {
	if mk := c.takeSkipped(h.DH, h.N); mk != nil {
		return mk, nil
	}

	if h.Type == drTypeBoot {
		if !c.Initiator {
			return nil, ErrDecrypt
		}
		if c.CKr != nil {
			// Le répondeur est déjà passé au ratchet : il n'envoie plus de démarrage. Comme la chaîne
			// de démarrage se recalcule depuis les clés statiques, la recréer permettrait de rejouer
			// d'anciens messages : seules les clés déjà mises de côté restent valables.
			return nil, ErrDRReplay
		}
		if c.BootRecv == nil || !bytes.Equal(c.BootRecv.DH, h.DH) {
			out, err := e.myX.ECDH(mustPub(h.DH))
			if err != nil {
				return nil, ErrDecrypt
			}
			ck, err := bootCK(e.SK, out)
			if err != nil {
				return nil, err
			}
			c.BootRecv = &chainState{DH: h.DH, CK: ck}
		}
		if err := c.skipTo(h.DH, &c.BootRecv.CK, &c.BootRecv.N, h.N); err != nil {
			return nil, err
		}
		var mk []byte
		c.BootRecv.CK, mk = kdfCK(c.BootRecv.CK)
		c.BootRecv.N++
		return mk, nil
	}

	if c.DHr == nil || !bytes.Equal(h.DH, c.DHr) {
		if c.CKr != nil {
			if err := c.skipTo(c.DHr, &c.CKr, &c.Nr, h.PN); err != nil {
				return nil, err
			}
		}
		if err := c.dhRatchet(h.DH); err != nil {
			return nil, err
		}
		// Le répondeur a reçu le ratchet de l'initiateur : fin de la chaîne de démarrage.
		// L'initiateur reçoit un message ratchet du répondeur : celui-ci n'enverra plus de boot.
		c.BootSend = nil
		c.BootRecv = nil
	}
	if c.CKr == nil {
		return nil, ErrDecrypt
	}
	if err := c.skipTo(c.DHr, &c.CKr, &c.Nr, h.N); err != nil {
		return nil, err
	}
	var mk []byte
	c.CKr, mk = kdfCK(c.CKr)
	c.Nr++
	return mk, nil
}

func mustPub(b []byte) *ecdh.PublicKey {
	p, err := ecdh.X25519().NewPublicKey(b)
	if err != nil {
		// NewPublicKey n'échoue que sur une mauvaise longueur, déjà contrôlée par l'en-tête.
		panic(err)
	}
	return p
}

func (c *Session) dhRatchet(theirDH []byte) error {
	c.PN = c.Ns
	c.Ns, c.Nr = 0, 0
	c.DHr = append([]byte(nil), theirDH...)
	out, err := dh(c.DHsPriv, c.DHr)
	if err != nil {
		return ErrDecrypt
	}
	if c.RK, c.CKr, err = kdfRK(c.RK, out); err != nil {
		return err
	}
	k, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return err
	}
	c.DHsPriv = k.Bytes()
	if out, err = dh(c.DHsPriv, c.DHr); err != nil {
		return ErrDecrypt
	}
	c.RK, c.CKs, err = kdfRK(c.RK, out)
	return err
}

// ---------- contenu clair v2 ----------

func encodeInnerV2(p Plain) ([]byte, error) {
	if len(p.Body) > MaxTextV2 {
		return nil, errors.New("message trop long")
	}
	inner := make([]byte, drInnerLen)
	inner[0] = p.Type
	copy(inner[1:17], p.ID[:])
	binary.BigEndian.PutUint64(inner[17:25], uint64(p.TS))
	binary.BigEndian.PutUint16(inner[25:27], uint16(len(p.Body)))
	copy(inner[27:], p.Body)
	return inner, nil
}

func decodeInnerV2(inner []byte) (Plain, error) {
	var p Plain
	if len(inner) != drInnerLen {
		return p, ErrDecrypt
	}
	p.Type = inner[0]
	copy(p.ID[:], inner[1:17])
	p.TS = int64(binary.BigEndian.Uint64(inner[17:25]))
	n := int(binary.BigEndian.Uint16(inner[25:27]))
	if n > MaxTextV2 {
		return p, ErrDecrypt
	}
	p.Body = append([]byte(nil), inner[27:27+n]...)
	return p, nil
}
