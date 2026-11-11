package main

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/rand"
	"testing"
)

type party struct {
	sign ed25519.PrivateKey
	ik   ed25519.PublicKey
	x    *ecdh.PrivateKey
}

func newParty(t *testing.T) party {
	t.Helper()
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	x, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return party{sign: priv, ik: pub, x: x}
}

func TestInviteRoundTrip(t *testing.T) {
	a := newParty(t)
	inv := Invite{V: 2, IK: b64url(a.ik), XK: a.x.PublicKey().Bytes(),
		R: []inviteRelay{{U: "https://192.168.1.252:8443", FP: make([]byte, 32), W: make([]byte, 16)}},
		S: make([]byte, SecretLen)}
	s, err := EncodeInvite(inv)
	if err != nil {
		t.Fatal(err)
	}
	got, err := DecodeInvite(s)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got.IK, inv.IK) || !bytes.Equal(got.XK, inv.XK) || got.R[0].U != inv.R[0].U || !bytes.Equal(got.S, inv.S) {
		t.Fatal("invitation différente après aller-retour")
	}
	for _, bad := range []string{"", "!!!",
		EncodeOrPanic(Invite{V: 1, IK: inv.IK, XK: inv.XK, R: inv.R, S: inv.S}),
		EncodeOrPanic(Invite{V: 2, IK: inv.IK, XK: inv.XK, R: inv.R}),
		EncodeOrPanic(Invite{V: 2, IK: inv.IK, XK: inv.XK, S: inv.S})} {
		if _, err := DecodeInvite(bad); err == nil {
			t.Fatalf("invitation invalide acceptée : %q", bad)
		}
	}
}

func EncodeOrPanic(inv Invite) string {
	s, err := EncodeInvite(inv)
	if err != nil {
		panic(err)
	}
	return s
}

func TestFingerprintParsing(t *testing.T) {
	fp := "FB:2B:01:81:C4:A7:E4:EA:3C:C9:DE:31:EA:A4:96:5A:D4:7E:C0:D6:F6:B7:4B:91:4F:DD:FB:FC:89:C6:B0:EA"
	b, err := ParseFingerprint(fp)
	if err != nil {
		t.Fatal(err)
	}
	if FormatFingerprint(b) != fp {
		t.Fatal("aller-retour de l'empreinte incorrect")
	}
	if _, err := ParseFingerprint("FB:2B"); err == nil {
		t.Fatal("empreinte trop courte acceptée")
	}
}

func pairSetup(t *testing.T) (inviter, scanner party, secret []byte, payload PairPayload) {
	t.Helper()
	inviter, scanner = newParty(t), newParty(t)
	secret = make([]byte, SecretLen)
	rand.Read(secret)
	payload = PairPayload{IK: scanner.ik, XK: scanner.x.PublicKey().Bytes(), W: bytes.Repeat([]byte{7}, 16),
		FP: bytes.Repeat([]byte{9}, 32), URL: "https://192.168.1.252:8443"}
	return
}

func TestPairRoundTrip(t *testing.T) {
	inv, sc, secret, p := pairSetup(t)
	blob, err := SealPair(sc.sign, inv.ik, inv.x.PublicKey().Bytes(), secret, p)
	if err != nil {
		t.Fatal(err)
	}
	if len(blob) != BlobSize || blob[0] != pairVersion {
		t.Fatal("format du bloc d'appairage incorrect")
	}
	got, err := OpenPair(inv.x, inv.ik, secret, blob)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got.IK, p.IK) || !bytes.Equal(got.XK, p.XK) || !bytes.Equal(got.W, p.W) ||
		!bytes.Equal(got.FP, p.FP) || got.URL != p.URL {
		t.Fatal("contenu différent après aller-retour")
	}
}

func TestPairRejections(t *testing.T) {
	inv, sc, secret, p := pairSetup(t)
	blob, _ := SealPair(sc.sign, inv.ik, inv.x.PublicKey().Bytes(), secret, p)
	other := make([]byte, SecretLen)
	if _, err := OpenPair(inv.x, inv.ik, other, blob); err == nil {
		t.Fatal("mauvais secret accepté")
	}
	third := newParty(t)
	if _, err := OpenPair(third.x, third.ik, secret, blob); err == nil {
		t.Fatal("bloc accepté par un autre destinataire")
	}
	for _, i := range []int{0, 1, 40, 500, BlobSize - 1} {
		bad := bytes.Clone(blob)
		bad[i] ^= 1
		if _, err := OpenPair(inv.x, inv.ik, secret, bad); err == nil {
			t.Fatalf("bloc altéré à l'octet %d accepté", i)
		}
	}
	// Quelqu'un qui a vu le QR annonce l'ik de la victime avec sa propre clé : signature refusée.
	mallory := newParty(t)
	forged := p
	forged.XK = mallory.x.PublicKey().Bytes()
	blob2, _ := SealPair(mallory.sign, inv.ik, inv.x.PublicKey().Bytes(), secret, forged)
	if _, err := OpenPair(inv.x, inv.ik, secret, blob2); err == nil {
		t.Fatal("ik usurpé accepté")
	}
	if _, err := SealPair(sc.sign, inv.ik, inv.x.PublicKey().Bytes(), secret, PairPayload{IK: p.IK, XK: p.XK, W: p.W, FP: p.FP, URL: "http://x"}); err == nil {
		t.Fatal("URL non https acceptée")
	}
}
