package main

// Appairage en un seul scan (MESSAGES.md, « Appairage »).
//
// L'invitant montre un QR qui contient ses clés publiques, la boîte qu'il réserve au contact et
// un secret aléatoire à usage unique. Celui qui scanne crée sa propre boîte pour l'invitant,
// puis dépose dans la boîte de l'invitant un bloc d'appairage :
//
//   version = 3 (1) | X25519 éphémère (32) | AES-256-GCM(contenu 1999 o) + tag (16)
//
// Contenu : ik (32) | xk (32) | write_id (16) | empreinte du relais (32) | longueur de l'URL (2) |
//           URL du relais | signature Ed25519 (64) | zéros
//
// Clé et nonce = HKDF-SHA256(X25519(éphémère, xk invitant) || secret,
//                            sel = ik invitant || éphémère, info = "relay-pair-v1") → 32 + 12 octets.
// Données associées = "relay-pair-v1" || ik invitant || éphémère.
// La signature, par la clé d'identité de celui qui scanne, couvre
//   "relay-pair-v1/sig" || ik invitant || SHA-256(secret) || contenu avant la signature :
// sans elle, quelqu'un qui aurait photographié le QR pourrait annoncer l'ik d'un autre avec sa
// propre clé xk, et l'empreinte affichée (calculée sur ik) ne le trahirait pas.

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"strings"
)

const (
	pairVersion  = 3
	pairPlainLen = BlobSize - 1 - 32 - tagLen // 1999
	pairInfo     = "relay-pair-v1"
	pairSigCtx   = "relay-pair-v1/sig"
	SecretLen    = 32
	maxURL       = 255
)

var ErrPair = errors.New("bloc d'appairage invalide")

// PairPayload est ce que celui qui scanne transmet à l'invitant.
type PairPayload struct {
	IK  []byte
	XK  []byte
	W   []byte // write_id de la boîte créée pour l'invitant
	FP  []byte // empreinte du relais de cette boîte
	URL string // relais de cette boîte
}

func (p PairPayload) body() ([]byte, error) {
	if len(p.IK) != 32 || len(p.XK) != 32 || len(p.W) != 16 || len(p.FP) != 32 ||
		!strings.HasPrefix(p.URL, "https://") || len(p.URL) > maxURL {
		return nil, ErrPair
	}
	var n [2]byte
	binary.BigEndian.PutUint16(n[:], uint16(len(p.URL)))
	return concat(p.IK, p.XK, p.W, p.FP, n[:], []byte(p.URL)), nil
}

func pairSigned(inviterIK, secret, body []byte) []byte {
	h := sha256.Sum256(secret)
	return concat([]byte(pairSigCtx), inviterIK, h[:], body)
}

func pairAEAD(shared, secret, inviterIK, ePub []byte) ([]byte, []byte, error) {
	okm, err := hkdf.Key(sha256.New, concat(shared, secret), concat(inviterIK, ePub), pairInfo, 44)
	if err != nil {
		return nil, nil, err
	}
	return okm[:32], okm[32:], nil
}

// SealPair construit le bloc d'appairage de celui qui scanne, pour l'invitant.
func SealPair(me ed25519.PrivateKey, inviterIK, inviterXK, secret []byte, p PairPayload) ([]byte, error) {
	if len(secret) != SecretLen || len(inviterIK) != 32 {
		return nil, ErrPair
	}
	body, err := p.body()
	if err != nil {
		return nil, err
	}
	xk, err := ecdh.X25519().NewPublicKey(inviterXK)
	if err != nil {
		return nil, ErrPair
	}
	plain := make([]byte, pairPlainLen)
	copy(plain, body)
	copy(plain[len(body):], ed25519.Sign(me, pairSigned(inviterIK, secret, body)))

	eph, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	shared, err := eph.ECDH(xk)
	if err != nil {
		return nil, ErrPair
	}
	ePub := eph.PublicKey().Bytes()
	key, nonce, err := pairAEAD(shared, secret, inviterIK, ePub)
	if err != nil {
		return nil, err
	}
	gcm, err := newGCM(key)
	if err != nil {
		return nil, err
	}
	blob := append([]byte{pairVersion}, ePub...)
	blob = gcm.Seal(blob, nonce, plain, concat([]byte(pairInfo), inviterIK, ePub))
	if len(blob) != BlobSize {
		return nil, errors.New("taille de bloc inattendue")
	}
	return blob, nil
}

// OpenPair déchiffre et vérifie un bloc d'appairage reçu par l'invitant.
func OpenPair(myX *ecdh.PrivateKey, myIK, secret, blob []byte) (PairPayload, error) {
	var p PairPayload
	if len(blob) != BlobSize || blob[0] != pairVersion || len(secret) != SecretLen {
		return p, ErrPair
	}
	ePub := blob[1:33]
	pub, err := ecdh.X25519().NewPublicKey(ePub)
	if err != nil {
		return p, ErrPair
	}
	shared, err := myX.ECDH(pub)
	if err != nil {
		return p, ErrPair
	}
	key, nonce, err := pairAEAD(shared, secret, myIK, ePub)
	if err != nil {
		return p, err
	}
	gcm, err := newGCM(key)
	if err != nil {
		return p, err
	}
	plain, err := gcm.Open(nil, nonce, blob[33:], concat([]byte(pairInfo), myIK, ePub))
	if err != nil || len(plain) != pairPlainLen {
		return p, ErrPair
	}
	n := int(binary.BigEndian.Uint16(plain[112:114]))
	if n > maxURL {
		return p, ErrPair
	}
	end := 114 + n
	p = PairPayload{
		IK:  bytes.Clone(plain[0:32]),
		XK:  bytes.Clone(plain[32:64]),
		W:   bytes.Clone(plain[64:80]),
		FP:  bytes.Clone(plain[80:112]),
		URL: string(plain[114:end]),
	}
	body, err := p.body()
	if err != nil {
		return PairPayload{}, err
	}
	if !ed25519.Verify(p.IK, pairSigned(myIK, secret, body), plain[end:end+ed25519.SignatureSize]) {
		return PairPayload{}, ErrPair
	}
	return p, nil
}
