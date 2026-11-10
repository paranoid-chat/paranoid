package main

// Fonctions pratiques pour utiliser relaycli comme « appli PC » face au téléphone :
// QR dans le terminal ou en PNG, invitation lue sur l'entrée standard, discussion en direct.

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"sync"
	"time"

	qrcode "github.com/skip2/go-qrcode"
)

// terminalQR dessine le QR avec des demi-blocs. go-qrcode dessine les modules sombres avec la
// couleur de fond du terminal : c'est le bon sens sur un terminal sombre (Windows Terminal,
// PowerShell). Sur un terminal clair, il faut inverser.
func terminalQR(text string, darkTerminal bool) (string, error) {
	q, err := qrcode.New(text, qrcode.Medium)
	if err != nil {
		return "", err
	}
	return q.ToSmallString(!darkTerminal), nil
}

func writeQRPNG(text, path string) error {
	return qrcode.WriteFile(text, qrcode.Medium, 768, path)
}

func readAllStdin() (string, error) {
	data, err := io.ReadAll(io.LimitReader(os.Stdin, 64<<10))
	if err != nil {
		return "", err
	}
	s := strings.TrimSpace(strings.TrimPrefix(string(data), "\xef\xbb\xbf"))
	if s == "" {
		return "", errors.New("aucune invitation reçue sur l'entrée standard")
	}
	return s, nil
}

// cmdChat ouvre une discussion : chaque ligne tapée est envoyée, les messages reçus s'affichent
// au fil de l'eau (relève toutes les 3 s).
func cmdChat(s *State, args []string) error {
	if len(args) != 1 {
		return errors.New("usage : chat NOM")
	}
	name := args[0]
	c := s.Contacts[name]
	if c == nil {
		return fmt.Errorf("contact %s inconnu", name)
	}
	if c.IK == nil || c.Their == nil || c.MyBox == nil {
		return fmt.Errorf("l'échange d'invitations avec %s n'est pas terminé (invite et add)", name)
	}
	fmt.Printf("Discussion avec %s, empreinte %s\n", name, SafetyNumber(c.IK))
	fmt.Println("Tape un message puis Entrée. /quit pour quitter.")

	var mu sync.Mutex // l'état (sessions, numéros) est partagé entre la relève et l'envoi
	poll := func() error {
		mu.Lock()
		defer mu.Unlock()
		_, err := recvFrom(s, name)
		return err
	}
	if err := poll(); err != nil {
		fmt.Fprintln(os.Stderr, "relève :", err)
	}

	stop := make(chan struct{})
	done := make(chan struct{})
	go func() {
		defer close(done)
		t := time.NewTicker(3 * time.Second)
		defer t.Stop()
		last := ""
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				msg := ""
				if err := poll(); err != nil {
					msg = err.Error()
				}
				// N'affiche une erreur qu'à son apparition ou à sa disparition.
				if msg != last {
					if msg != "" {
						fmt.Fprintln(os.Stderr, "relève :", msg)
					} else {
						fmt.Fprintln(os.Stderr, "relève : connexion rétablie")
					}
					last = msg
				}
			}
		}
	}()

	sc := bufio.NewScanner(os.Stdin)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" {
			continue
		}
		if line == "/quit" {
			break
		}
		mu.Lock()
		_, err := sendMessage(s, name, line)
		mu.Unlock()
		if err != nil {
			fmt.Fprintln(os.Stderr, "envoi impossible :", err)
		}
	}
	close(stop)
	<-done
	return sc.Err()
}
