package main

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/rand"
	"errors"
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

func sample() Plain {
	p := Plain{Type: TypeText, TS: 1_800_000_000, Body: []byte("bonjour, ceci est un test é€")}
	rand.Read(p.ID[:])
	return p
}

func TestSealOpenRoundTrip(t *testing.T) {
	a, b := newParty(t), newParty(t)
	p := sample()
	blob, err := Seal(a.sign, b.x.PublicKey(), b.ik, p)
	if err != nil {
		t.Fatal(err)
	}
	if len(blob) != BlobSize {
		t.Fatalf("taille %d", len(blob))
	}
	got, err := Open(b.x, b.ik, a.ik, blob)
	if err != nil {
		t.Fatal(err)
	}
	if got.Type != p.Type || got.ID != p.ID || got.TS != p.TS || !bytes.Equal(got.Body, p.Body) {
		t.Fatalf("contenu différent : %+v", got)
	}
}

func TestSizesAndLimits(t *testing.T) {
	a, b := newParty(t), newParty(t)
	for _, n := range []int{0, 1, MaxText} {
		p := sample()
		p.Body = bytes.Repeat([]byte{'x'}, n)
		blob, err := Seal(a.sign, b.x.PublicKey(), b.ik, p)
		if err != nil {
			t.Fatalf("n=%d : %v", n, err)
		}
		if len(blob) != BlobSize {
			t.Fatalf("n=%d : taille %d", n, len(blob))
		}
		got, err := Open(b.x, b.ik, a.ik, blob)
		if err != nil || len(got.Body) != n {
			t.Fatalf("n=%d : %v", n, err)
		}
	}
	p := sample()
	p.Body = make([]byte, MaxText+1)
	if _, err := Seal(a.sign, b.x.PublicKey(), b.ik, p); err == nil {
		t.Fatal("message trop long accepté")
	}
}

func TestTwoSealsDiffer(t *testing.T) {
	a, b := newParty(t), newParty(t)
	p := sample()
	b1, _ := Seal(a.sign, b.x.PublicKey(), b.ik, p)
	b2, _ := Seal(a.sign, b.x.PublicKey(), b.ik, p)
	if bytes.Equal(b1, b2) {
		t.Fatal("deux chiffrements identiques : la clé éphémère ou le nonce n'est pas aléatoire")
	}
}

func TestTamperingDetected(t *testing.T) {
	a, b := newParty(t), newParty(t)
	blob, _ := Seal(a.sign, b.x.PublicKey(), b.ik, sample())
	for _, i := range []int{1, 1 + ephLen, headerLen, headerLen + 100, BlobSize - 1} {
		bad := append([]byte(nil), blob...)
		bad[i] ^= 0x01
		if _, err := Open(b.x, b.ik, a.ik, bad); err == nil {
			t.Fatalf("octet %d modifié non détecté", i)
		}
	}
	bad := append([]byte(nil), blob...)
	bad[0] = 2
	if _, err := Open(b.x, b.ik, a.ik, bad); err == nil {
		t.Fatal("version inconnue acceptée")
	}
	if _, err := Open(b.x, b.ik, a.ik, blob[:BlobSize-1]); err == nil {
		t.Fatal("bloc tronqué accepté")
	}
}

func TestWrongRecipient(t *testing.T) {
	a, b, c := newParty(t), newParty(t), newParty(t)
	blob, _ := Seal(a.sign, b.x.PublicKey(), b.ik, sample())
	if _, err := Open(c.x, c.ik, a.ik, blob); !errors.Is(err, ErrDecrypt) {
		t.Fatalf("un tiers a pu déchiffrer : %v", err)
	}
}

func TestWrongSender(t *testing.T) {
	a, b, m := newParty(t), newParty(t), newParty(t)
	// m connaît les clés publiques de b et se fait passer pour a.
	blob, _ := Seal(m.sign, b.x.PublicKey(), b.ik, sample())
	if _, err := Open(b.x, b.ik, a.ik, blob); !errors.Is(err, ErrSignature) {
		t.Fatalf("usurpation non détectée : %v", err)
	}
}

func TestSignatureBoundToRecipient(t *testing.T) {
	// Un message signé par a pour b ne doit pas être réexpédiable à c comme s'il lui était destiné :
	// b déchiffre, puis rechiffre le même contenu signé pour c.
	a, b, c := newParty(t), newParty(t), newParty(t)
	inner := make([]byte, innerLen)
	rand.Read(inner[sigLen:])
	copy(inner[:sigLen], ed25519.Sign(a.sign, signedPart(b.ik, inner)))
	if !ed25519.Verify(a.ik, signedPart(b.ik, inner), inner[:sigLen]) {
		t.Fatal("signature valide refusée")
	}
	if ed25519.Verify(a.ik, signedPart(c.ik, inner), inner[:sigLen]) {
		t.Fatal("la signature vaut aussi pour un autre destinataire")
	}
}

func TestInviteRoundTrip(t *testing.T) {
	a := newParty(t)
	inv := Invite{V: 1, IK: b64url(a.ik), XK: a.x.PublicKey().Bytes(),
		R: []inviteRelay{{U: "https://192.168.1.252:8443", FP: make([]byte, 32), W: make([]byte, 16)}}}
	s, err := EncodeInvite(inv)
	if err != nil {
		t.Fatal(err)
	}
	got, err := DecodeInvite(s)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got.IK, inv.IK) || !bytes.Equal(got.XK, inv.XK) || got.R[0].U != inv.R[0].U {
		t.Fatal("invitation différente après aller-retour")
	}
	for _, bad := range []string{"", "!!!", EncodeOrPanic(Invite{V: 2, IK: inv.IK, XK: inv.XK, R: inv.R}),
		EncodeOrPanic(Invite{V: 1, IK: inv.IK, XK: inv.XK})} {
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
