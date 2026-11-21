package main

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/rand"
	"errors"
	"fmt"
	"testing"
)

type peer struct {
	name string
	ik   ed25519.PublicKey
	x    *ecdh.PrivateKey
	env  *drEnv
	s    *Session
}

func newPeers(t *testing.T) (init, resp *peer) {
	t.Helper()
	mk := func(n string) *peer {
		ik, _, err := ed25519.GenerateKey(rand.Reader)
		if err != nil {
			t.Fatal(err)
		}
		x, err := ecdh.X25519().GenerateKey(rand.Reader)
		if err != nil {
			t.Fatal(err)
		}
		return &peer{name: n, ik: ik, x: x}
	}
	a, b := mk("a"), mk("b")
	for _, p := range [][2]*peer{{a, b}, {b, a}} {
		me, other := p[0], p[1]
		e, err := newDREnv(me.ik, me.x, other.ik, other.x.PublicKey().Bytes())
		if err != nil {
			t.Fatal(err)
		}
		me.env = e
		if me.s, err = NewSession(e); err != nil {
			t.Fatal(err)
		}
	}
	if a.s.Initiator == b.s.Initiator {
		t.Fatal("les deux côtés ont le même rôle")
	}
	if !bytes.Equal(a.env.SK, b.env.SK) || !bytes.Equal(a.env.AD, b.env.AD) {
		t.Fatal("SK ou AD différents des deux côtés")
	}
	if a.s.Initiator {
		return a, b
	}
	return b, a
}

func (p *peer) send(t *testing.T, text string) []byte {
	t.Helper()
	inner, err := encodeInnerV2(Plain{Type: TypeText, Body: []byte(text)})
	if err != nil {
		t.Fatal(err)
	}
	blob, err := p.s.Encrypt(p.env, inner)
	if err != nil {
		t.Fatalf("%s envoie %q : %v", p.name, text, err)
	}
	if len(blob) != BlobSize {
		t.Fatalf("taille %d", len(blob))
	}
	return blob
}

func (p *peer) recv(t *testing.T, blob []byte, want string) {
	t.Helper()
	inner, err := p.s.Decrypt(p.env, blob)
	if err != nil {
		t.Fatalf("%s reçoit %q : %v", p.name, want, err)
	}
	got, err := decodeInnerV2(inner)
	if err != nil || string(got.Body) != want {
		t.Fatalf("%s : reçu %q, attendu %q (%v)", p.name, got.Body, want, err)
	}
}

func (p *peer) recvFails(t *testing.T, blob []byte) error {
	t.Helper()
	before := p.s.clone()
	_, err := p.s.Decrypt(p.env, blob)
	if err == nil {
		t.Fatalf("%s a accepté un bloc qui devait être refusé", p.name)
	}
	if !sessionsEqual(before, p.s) {
		t.Fatalf("%s : l'état a changé après un échec", p.name)
	}
	return err
}

func sessionsEqual(a, b *Session) bool {
	return fmt.Sprintf("%+v", *a.clone()) == fmt.Sprintf("%+v", *b.clone())
}

func TestDRInitiatorFirst(t *testing.T) {
	a, b := newPeers(t)
	for round := 0; round < 5; round++ {
		for i := 0; i < 3; i++ {
			msg := fmt.Sprintf("a→b %d.%d", round, i)
			b.recv(t, a.send(t, msg), msg)
		}
		for i := 0; i < 2; i++ {
			msg := fmt.Sprintf("b→a %d.%d", round, i)
			a.recv(t, b.send(t, msg), msg)
		}
	}
	if b.s.BootSend != nil || a.s.BootRecv != nil {
		t.Fatal("pas de chaîne de démarrage attendue")
	}
}

func TestDRResponderFirst(t *testing.T) {
	a, b := newPeers(t)
	m1, m2 := b.send(t, "boot 1"), b.send(t, "boot 2")
	if m1[1] != drTypeBoot {
		t.Fatal("le répondeur devrait utiliser la chaîne de démarrage")
	}
	a.recv(t, m1, "boot 1")
	a.recv(t, m2, "boot 2")
	b.recv(t, a.send(t, "réponse"), "réponse")
	if b.s.BootSend != nil {
		t.Fatal("la chaîne de démarrage devrait être abandonnée après réception")
	}
	m3 := b.send(t, "ratchet")
	if m3[1] != drTypeRatch {
		t.Fatal("le répondeur devrait être passé au ratchet")
	}
	a.recv(t, m3, "ratchet")
	a.recv(t, b.send(t, "encore"), "encore")
	b.recv(t, a.send(t, "fin"), "fin")
}

func TestDRSimultaneousFirst(t *testing.T) {
	a, b := newPeers(t)
	ma := a.send(t, "de a")
	mb := b.send(t, "de b")
	a.recv(t, mb, "de b")
	b.recv(t, ma, "de a")
	mb2 := b.send(t, "b après")
	ma2 := a.send(t, "a après")
	a.recv(t, mb2, "b après")
	b.recv(t, ma2, "a après")
}

func TestDROutOfOrder(t *testing.T) {
	a, b := newPeers(t)
	m := make([][]byte, 5)
	for i := range m {
		m[i] = a.send(t, fmt.Sprint("m", i))
	}
	b.recv(t, m[3], "m3")
	b.recv(t, m[0], "m0")
	a.recv(t, b.send(t, "r"), "r")
	n0 := a.send(t, "n0")
	b.recv(t, n0, "n0")
	b.recv(t, m[4], "m4")
	b.recv(t, m[1], "m1")
	b.recv(t, m[2], "m2")
	if len(b.s.Skipped) != 0 {
		t.Fatalf("%d clés sautées restantes", len(b.s.Skipped))
	}
}

func TestDRLostMessagesAcrossRatchet(t *testing.T) {
	a, b := newPeers(t)
	b.recv(t, a.send(t, "x"), "x")
	a.send(t, "perdu 1")
	a.send(t, "perdu 2")
	a.recv(t, b.send(t, "y"), "y")
	b.recv(t, a.send(t, "z"), "z")
	if len(b.s.Skipped) != 2 {
		t.Fatalf("%d clés sautées, attendu 2", len(b.s.Skipped))
	}
}

func TestDRBootReplayAfterSwitch(t *testing.T) {
	a, b := newPeers(t)
	m0, m1, m2 := b.send(t, "b0"), b.send(t, "b1"), b.send(t, "b2")
	a.recv(t, m0, "b0")
	a.recv(t, m2, "b2")
	b.recv(t, a.send(t, "réponse"), "réponse")
	a.recv(t, b.send(t, "ratchet"), "ratchet")
	if err := a.recvFails(t, m0); !errors.Is(err, ErrDRReplay) {
		t.Fatalf("rejeu d'un message de démarrage après bascule : %v", err)
	}
	a.recv(t, m1, "b1")
	a.recvFails(t, m1)
}

func TestDRBootOutOfOrder(t *testing.T) {
	a, b := newPeers(t)
	m0, m1, m2 := b.send(t, "b0"), b.send(t, "b1"), b.send(t, "b2")
	a.recv(t, m2, "b2")
	a.recv(t, m0, "b0")
	a.recv(t, m1, "b1")
}

func TestDRReplayRejected(t *testing.T) {
	a, b := newPeers(t)
	m := a.send(t, "une fois")
	b.recv(t, m, "une fois")
	if err := b.recvFails(t, m); !errors.Is(err, ErrDRReplay) {
		t.Fatalf("rejeu : erreur %v", err)
	}
	mb := b.send(t, "boot-like")
	a.recv(t, mb, "boot-like")
	a.recvFails(t, mb)
}

func TestDRTamperingDoesNotCorruptState(t *testing.T) {
	a, b := newPeers(t)
	m := a.send(t, "ok")
	for _, i := range []int{1, 2, 20, 34, 38, drHeaderLen, drHeaderLen + 500, BlobSize - 1} {
		bad := append([]byte(nil), m...)
		bad[i] ^= 0x40
		b.recvFails(t, bad)
	}
	b.recv(t, m, "ok")
}

func TestDRForgedNewRatchetKey(t *testing.T) {
	a, b := newPeers(t)
	b.recv(t, a.send(t, "1"), "1")
	k, _ := ecdh.X25519().GenerateKey(rand.Reader)
	bad := drHeader{Type: drTypeRatch, DH: k.PublicKey().Bytes(), PN: 0, N: 0}.bytes()
	bad = append(bad, make([]byte, BlobSize-drHeaderLen)...)
	rand.Read(bad[drHeaderLen:])
	b.recvFails(t, bad)
	badBoot := drHeader{Type: drTypeBoot, DH: k.PublicKey().Bytes()}.bytes()
	badBoot = append(badBoot, make([]byte, BlobSize-drHeaderLen)...)
	a.recvFails(t, badBoot)
	b.recvFails(t, badBoot)
	b.recv(t, a.send(t, "2"), "2")
}

func TestDRSkipLimit(t *testing.T) {
	a, b := newPeers(t)
	for i := 0; i <= maxSkip; i++ {
		a.send(t, "x")
	}
	if err := b.recvFails(t, a.send(t, "trop loin")); !errors.Is(err, ErrDRSkip) {
		t.Fatalf("limite de saut : erreur %v", err)
	}
}

func TestDRWrongPeer(t *testing.T) {
	a, b := newPeers(t)
	c, _ := newPeers(t)
	m := a.send(t, "pour b")
	c.recvFails(t, m)
	b.recv(t, m, "pour b")
}

func TestDRPostCompromiseSecurity(t *testing.T) {
	a, b := newPeers(t)
	b.recv(t, a.send(t, "avant"), "avant")
	stolen := &peer{name: "attaquant", env: b.env, s: b.s.clone()}

	m1 := a.send(t, "lisible par l'attaquant")
	stolen.recv(t, m1, "lisible par l'attaquant")
	b.recv(t, m1, "lisible par l'attaquant")

	a.recv(t, b.send(t, "réponse 1"), "réponse 1")
	m2 := a.send(t, "m2")
	b.recv(t, m2, "m2")
	a.recv(t, b.send(t, "réponse 2"), "réponse 2")
	m3 := a.send(t, "m3")
	b.recv(t, m3, "m3")

	_, err2 := stolen.s.Decrypt(stolen.env, m2)
	_, err3 := stolen.s.Decrypt(stolen.env, m3)
	if err3 == nil {
		t.Fatal("l'attaquant lit encore la conversation après deux allers-retours")
	}
	t.Logf("attaquant : m2 %v, m3 %v", err2, err3)
}

func TestDRForwardSecrecy(t *testing.T) {
	a, b := newPeers(t)
	m := a.send(t, "secret")
	b.recv(t, m, "secret")
	b.recv(t, a.send(t, "suivant"), "suivant")
	snapshot := &peer{name: "saisie", env: b.env, s: b.s.clone()}
	snapshot.recvFails(t, m)
}

func TestDRStatePersistence(t *testing.T) {
	a, b := newPeers(t)
	m := a.send(t, "1")
	b.s = b.s.clone()
	b.recv(t, m, "1")
	a.s = a.s.clone()
	a.recv(t, b.send(t, "2"), "2")
}

func TestDRMaxText(t *testing.T) {
	a, b := newPeers(t)
	long := string(bytes.Repeat([]byte{'z'}, MaxTextV2))
	b.recv(t, a.send(t, long), long)
	if _, err := encodeInnerV2(Plain{Body: make([]byte, MaxTextV2+1)}); err == nil {
		t.Fatal("message trop long accepté")
	}
}
