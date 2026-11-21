package main

import (
	"crypto/rand"
	"errors"
	"flag"
	"fmt"
	"os"
	"sync"
	"time"
)

func cmdPanic(s *State, dir string, args []string) error {
	fs := flag.NewFlagSet("panic", flag.ContinueOnError)
	yes := fs.Bool("oui", false, "confirme la destruction (irréversible)")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if !*yes {
		return errors.New("irréversible : relance avec « panic --oui »")
	}

	type job struct {
		name string
		box  *TheirBox
		blob []byte
	}
	var jobs []job
	for _, name := range sortedNames(s) {
		c := s.Contacts[name]
		if c.IK == nil || c.Their == nil || c.MyBox == nil || c.Burned {
			continue
		}
		e, err := s.ensureSession(c)
		if err != nil {
			continue
		}
		p := Plain{Type: TypeAlert, TS: time.Now().Unix()}
		if _, err := rand.Read(p.ID[:]); err != nil {
			continue
		}
		inner, err := encodeInnerV2(p)
		if err != nil {
			continue
		}
		blob, err := c.Session.Encrypt(e, inner)
		if err != nil {
			continue
		}
		jobs = append(jobs, job{name, c.Their, blob})
	}

	var mu sync.Mutex
	sent := []string{}
	var wg sync.WaitGroup
	for _, j := range jobs {
		wg.Add(1)
		go func(j job) {
			defer wg.Done()
			cl := NewRelayClient(j.box.Relay)
			cl.http.Timeout = 4 * time.Second
			if _, err := cl.Deposit(j.box.WriteID, j.blob); err == nil {
				mu.Lock()
				sent = append(sent, j.name)
				mu.Unlock()
			}
		}(j)
	}
	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
	}

	if err := os.RemoveAll(dir); err != nil {
		return fmt.Errorf("effacement de %s : %w", dir, err)
	}
	mu.Lock()
	defer mu.Unlock()
	fmt.Printf("Alerte envoyée à %d contact(s) sur %d. Identité effacée (%s).\n", len(sent), len(jobs), dir)
	return nil
}
