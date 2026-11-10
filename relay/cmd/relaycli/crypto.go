package main

// Format de message v1 (MESSAGES.md).
//
// Bloc de 2048 octets déposé sur le relais :
//   version (1) | clé publique X25519 éphémère (32) | nonce (12) | chiffré AES-256-GCM (2003, dont tag 16)
//
// Contenu clair (1987 octets) :
//   signature Ed25519 (64) | type (1) | id (16) | horodatage (8) | longueur (2) | texte | zéros
//
// Clé : HKDF-SHA256(X25519(éphémère, X25519 du destinataire), sel = éph || X25519 dest, info = "relay-msg-v1").
// La signature de l'expéditeur couvre le contexte, la clé d'identité du destinataire et tout le contenu
// après la signature (padding compris) : elle n'est pas réutilisable pour un autre destinataire.

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"errors"
)

const (
	BlobSize    = 2048
	blobVersion = 1
	ephLen      = 32
	nonceLen    = 12
	tagLen      = 16
	headerLen   = 1 + ephLen + nonceLen
	innerLen    = BlobSize - headerLen - tagLen // 1987
	sigLen      = ed25519.SignatureSize
	innerHead   = 1 + 16 + 8 + 2
	MaxText     = innerLen - sigLen - innerHead // 1896 octets

	TypeText byte = 1

	sigContext = "relay-msg-v1/sig"
	kdfInfo    = "relay-msg-v1"
)

var ErrDecrypt = errors.New("message illisible (clé incorrecte ou bloc altéré)")
var ErrSignature = errors.New("signature de l'expéditeur invalide")

// Plain est le contenu clair d'un message.
type Plain struct {
	Type byte
	ID   [16]byte
	TS   int64
	Body []byte
}

func deriveKey(shared, ephPub, recipXK []byte) ([]byte, error) {
	salt := make([]byte, 0, len(ephPub)+len(recipXK))
	salt = append(salt, ephPub...)
	salt = append(salt, recipXK...)
	return hkdf.Key(sha256.New, shared, salt, kdfInfo, 32)
}

func newGCM(key []byte) (cipher.AEAD, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

func signedPart(recipIK ed25519.PublicKey, inner []byte) []byte {
	msg := make([]byte, 0, len(sigContext)+len(recipIK)+len(inner)-sigLen)
	msg = append(msg, sigContext...)
	msg = append(msg, recipIK...)
	msg = append(msg, inner[sigLen:]...)
	return msg
}

// Seal chiffre p pour le destinataire et le signe avec la clé d'identité de l'expéditeur.
func Seal(sender ed25519.PrivateKey, recipXK *ecdh.PublicKey, recipIK ed25519.PublicKey, p Plain) ([]byte, error) {
	if len(p.Body) > MaxText {
		return nil, errors.New("message trop long")
	}
	inner := make([]byte, innerLen)
	o := sigLen
	inner[o] = p.Type
	o++
	copy(inner[o:], p.ID[:])
	o += 16
	binary.BigEndian.PutUint64(inner[o:], uint64(p.TS))
	o += 8
	binary.BigEndian.PutUint16(inner[o:], uint16(len(p.Body)))
	o += 2
	copy(inner[o:], p.Body)
	copy(inner[:sigLen], ed25519.Sign(sender, signedPart(recipIK, inner)))

	eph, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	shared, err := eph.ECDH(recipXK)
	if err != nil {
		return nil, err
	}
	key, err := deriveKey(shared, eph.PublicKey().Bytes(), recipXK.Bytes())
	if err != nil {
		return nil, err
	}
	gcm, err := newGCM(key)
	if err != nil {
		return nil, err
	}

	blob := make([]byte, headerLen, BlobSize)
	blob[0] = blobVersion
	copy(blob[1:], eph.PublicKey().Bytes())
	if _, err := rand.Read(blob[1+ephLen : headerLen]); err != nil {
		return nil, err
	}
	blob = gcm.Seal(blob, blob[1+ephLen:headerLen], inner, blob[:1+ephLen])
	if len(blob) != BlobSize {
		return nil, errors.New("taille de bloc inattendue")
	}
	return blob, nil
}

// Open déchiffre un bloc reçu et vérifie qu'il a été signé par senderIK, pour recipIK.
func Open(recip *ecdh.PrivateKey, recipIK, senderIK ed25519.PublicKey, blob []byte) (Plain, error) {
	var p Plain
	if len(blob) != BlobSize {
		return p, ErrDecrypt
	}
	if blob[0] != blobVersion {
		return p, errors.New("version de message non prise en charge")
	}
	ephPub, err := ecdh.X25519().NewPublicKey(blob[1 : 1+ephLen])
	if err != nil {
		return p, ErrDecrypt
	}
	shared, err := recip.ECDH(ephPub)
	if err != nil {
		return p, ErrDecrypt
	}
	key, err := deriveKey(shared, blob[1:1+ephLen], recip.PublicKey().Bytes())
	if err != nil {
		return p, err
	}
	gcm, err := newGCM(key)
	if err != nil {
		return p, err
	}
	inner, err := gcm.Open(nil, blob[1+ephLen:headerLen], blob[headerLen:], blob[:1+ephLen])
	if err != nil || len(inner) != innerLen {
		return p, ErrDecrypt
	}
	if !ed25519.Verify(senderIK, signedPart(recipIK, inner), inner[:sigLen]) {
		return p, ErrSignature
	}
	o := sigLen
	p.Type = inner[o]
	o++
	copy(p.ID[:], inner[o:o+16])
	o += 16
	p.TS = int64(binary.BigEndian.Uint64(inner[o:]))
	o += 8
	n := int(binary.BigEndian.Uint16(inner[o:]))
	o += 2
	if n > MaxText {
		return p, ErrDecrypt
	}
	p.Body = append([]byte(nil), inner[o:o+n]...)
	return p, nil
}
