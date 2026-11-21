package main

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

func cmdChat(s *State, args []string) error {
	if len(args) != 1 {
		return errors.New("usage : chat NOM")
	}
	name := args[0]
	c := s.Contacts[name]
	if c == nil {
		return fmt.Errorf("contact %s inconnu", name)
	}
	if c.IK == nil && c.PairSecret != nil {
		if _, err := recvFrom(s, name); err != nil {
			return err
		}
	}
	if c.IK == nil || c.Their == nil || c.MyBox == nil {
		return fmt.Errorf("%s n'a pas encore scanné ton invitation : réessaie quand c'est fait", name)
	}
	fmt.Printf("Discussion avec %s, empreinte %s\n", name, SafetyNumber(c.IK))
	fmt.Println("Tape un message puis Entrée. /quit pour quitter.")

	var mu sync.Mutex
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
