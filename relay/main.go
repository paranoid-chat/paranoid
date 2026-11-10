package main

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

func fingerprint(der []byte) string {
	sum := sha256.Sum256(der)
	parts := make([]string, len(sum))
	for i, b := range sum {
		parts[i] = fmt.Sprintf("%02X", b)
	}
	return strings.Join(parts, ":")
}

func main() {
	listen := flag.String("listen", ":8443", "adresse d'écoute")
	certFile := flag.String("cert", "relay.crt", "certificat TLS (PEM)")
	keyFile := flag.String("key", "relay.key", "clé privée TLS (PEM)")
	dbPath := flag.String("db", "relay.db", "chemin de la base SQLite")
	push := flag.Bool("push", true, "réveils UnifiedPush (Web Push) après chaque dépôt")
	vapidPath := flag.String("vapid", "", "clé VAPID (PEM, créée si absente ; défaut : à côté de la base)")
	vapidSub := flag.String("vapid-sub", "mailto:relay@localhost", "contact VAPID transmis aux services de notification")
	pushPrivate := flag.Bool("push-allow-private", false, "autorise les services de notification sur un réseau privé (ntfy en LAN)")
	pushHTTP := flag.Bool("push-allow-http", false, "autorise les services de notification en http (tests uniquement)")
	flag.Parse()

	store, err := OpenStore(*dbPath)
	if err != nil {
		log.Fatalf("base: %v", err)
	}
	defer store.Close()

	cert, err := tls.LoadX509KeyPair(*certFile, *keyFile)
	if err != nil {
		log.Fatalf("certificat: %v", err)
	}

	srv := NewServer(DefaultConfig(), store)
	if *push {
		path := *vapidPath
		if path == "" {
			path = filepath.Join(filepath.Dir(*dbPath), "vapid.pem")
		}
		p, err := NewPusher(path, *vapidSub, *pushPrivate, *pushHTTP)
		if err != nil {
			log.Fatalf("notifications : %v", err)
		}
		srv.SetPusher(p)
	}
	httpSrv := &http.Server{
		Addr:    *listen,
		Handler: srv,
		TLSConfig: &tls.Config{
			MinVersion:   tls.VersionTLS13,
			Certificates: []tls.Certificate{cert},
		},
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    8 << 10,
		ErrorLog:          log.New(io.Discard, "", 0), // pas de journal applicatif
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	go func() {
		t := time.NewTicker(10 * time.Minute)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				if err := srv.Purge(); err != nil {
					log.Printf("purge: %v", err)
				}
			}
		}
	}()

	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		httpSrv.Shutdown(shutdownCtx)
	}()

	// Seul message de démarrage : adresse et empreinte à comparer avec celle de openssl.
	log.Printf("relais à l'écoute sur %s, empreinte SHA-256 : %s", *listen, fingerprint(cert.Certificate[0]))
	if err := httpSrv.ListenAndServeTLS("", ""); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatalf("serveur: %v", err)
		os.Exit(1)
	}
}
