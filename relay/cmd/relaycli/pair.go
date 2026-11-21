package main

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
	pairPlainLen = BlobSize - 1 - 32 - tagLen
	pairInfo     = "relay-pair-v1"
	pairSigCtx   = "relay-pair-v1/sig"
	SecretLen    = 32
	maxURL       = 255
)

var ErrPair = errors.New("bloc d'appairage invalide")

type PairPayload struct {
	IK  []byte
	XK  []byte
	W   []byte
	FP  []byte
	URL string
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
