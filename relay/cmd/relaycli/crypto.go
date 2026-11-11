package main

// Constantes et types communs aux formats de bloc (MESSAGES.md) :
//   - version 2 : message du Double Ratchet (dr.go) ;
//   - version 3 : bloc d'appairage, déposé une seule fois par celui qui scanne (pair.go).

import (
	"crypto/aes"
	"crypto/cipher"
	"errors"
)

const (
	BlobSize  = 2048
	tagLen    = 16
	nonceLen  = 12
	innerHead = 1 + 16 + 8 + 2 // type, identifiant, horodatage, longueur

	TypeText        byte = 1
	TypeAlert       byte = 2 // bouton rouge : l'expéditeur a détruit sa clé privée (corps vide)
	TypeRelayChange byte = 3 // l'expéditeur a changé de relais : ancienne identité effacée (corps vide)
)

var ErrDecrypt = errors.New("message illisible (clé incorrecte ou bloc altéré)")

// Plain est le contenu clair d'un message.
type Plain struct {
	Type byte
	ID   [16]byte
	TS   int64
	Body []byte
}

func newGCM(key []byte) (cipher.AEAD, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}
