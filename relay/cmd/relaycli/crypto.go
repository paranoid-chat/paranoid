package main

import (
	"crypto/aes"
	"crypto/cipher"
	"errors"
)

const (
	BlobSize  = 2048
	tagLen    = 16
	nonceLen  = 12
	innerHead = 1 + 16 + 8 + 2

	TypeText        byte = 1
	TypeAlert       byte = 2
	TypeRelayChange byte = 3
)

var ErrDecrypt = errors.New("message illisible (clé incorrecte ou bloc altéré)")

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
