package main

import (
	"testing"
	"time"
)

func TestLimiter(t *testing.T) {
	l := newLimiter(2, time.Minute) // capacité 2, 1 jeton toutes les 30 s
	t0 := time.Unix(1_800_000_000, 0)

	if ok, _ := l.allow("a", t0); !ok {
		t.Fatal("1er appel refusé")
	}
	if ok, _ := l.allow("a", t0); !ok {
		t.Fatal("2e appel refusé")
	}
	ok, retry := l.allow("a", t0)
	if ok {
		t.Fatal("3e appel accepté")
	}
	if retry <= 0 || retry > 31*time.Second {
		t.Fatalf("délai de reprise incohérent : %v", retry)
	}
	// Une autre clé n'est pas affectée.
	if ok, _ := l.allow("b", t0); !ok {
		t.Fatal("la clé b ne devrait pas être limitée")
	}
	// Recharge d'un jeton après 30 s.
	if ok, _ := l.allow("a", t0.Add(31*time.Second)); !ok {
		t.Fatal("jeton non rechargé après 31 s")
	}
	// Le plafond n'est jamais dépassé, même après une longue attente.
	later := t0.Add(24 * time.Hour)
	l.allow("a", later)
	l.allow("a", later)
	if ok, _ := l.allow("a", later); ok {
		t.Fatal("le plafond de capacité a été dépassé")
	}
}

func TestLimiterGC(t *testing.T) {
	l := newLimiter(2, time.Minute)
	t0 := time.Unix(1_800_000_000, 0)
	l.allow("a", t0)
	if len(l.m) != 1 {
		t.Fatal("entrée attendue")
	}
	l.gc(t0.Add(time.Hour)) // seau entièrement rechargé : entrée supprimée
	if len(l.m) != 0 {
		t.Fatal("l'entrée inactive aurait dû être supprimée")
	}
}

func TestNonceCache(t *testing.T) {
	c := newNonceCache()
	t0 := time.Unix(1_800_000_000, 0)
	ttl := 121 * time.Second

	if !c.add("n1", t0, ttl) {
		t.Fatal("premier ajout refusé")
	}
	if c.add("n1", t0.Add(time.Second), ttl) {
		t.Fatal("rejeu accepté")
	}
	if !c.add("n2", t0, ttl) {
		t.Fatal("un autre nonce devrait passer")
	}
	// Après expiration, le nonce peut être réutilisé (la fenêtre d'horodatage l'interdit déjà côté serveur).
	if !c.add("n1", t0.Add(ttl+time.Second), ttl) {
		t.Fatal("nonce expiré toujours refusé")
	}
	c.gc(t0.Add(time.Hour))
	if len(c.m) != 0 {
		t.Fatal("le nettoyage aurait dû vider le cache")
	}
}
