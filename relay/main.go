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
	"strconv"
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

// parseSize lit une taille en octets, avec un suffixe binaire facultatif : K, M, G ou T.
func parseSize(v string) (int64, error) {
	v = strings.ToUpper(strings.TrimSpace(v))
	mult := int64(1)
	if n := len(v); n > 0 {
		if i := strings.IndexByte("KMGT", v[n-1]); i >= 0 {
			mult = int64(1) << (10 * (i + 1))
			v = v[:n-1]
		}
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil || n < 0 || n > (1<<62)/mult {
		return 0, fmt.Errorf("taille invalide : %q", v)
	}
	return n * mult, nil
}

func human(n int64) string {
	if n == 0 {
		return "sans limite"
	}
	for i, u := range []string{"T", "G", "M", "K"} {
		if m := int64(1) << (10 * (4 - i)); n%m == 0 {
			return strconv.FormatInt(n/m, 10) + u
		}
	}
	return strconv.FormatInt(n, 10) + " o"
}

func sizeFlag(dst *int64) func(string) error {
	return func(v string) error {
		n, err := parseSize(v)
		if err == nil {
			*dst = n
		}
		return err
	}
}

func main() {
	listen := flag.String("listen", ":8443", "adresse d'écoute")
	certFile := flag.String("cert", "relay.crt", "certificat TLS (PEM)")
	keyFile := flag.String("key", "relay.key", "clé privée TLS (PEM)")
	dbPath := flag.String("db", "relay.db", "chemin de la base SQLite")
	cfg := DefaultConfig()
	flag.Func("max-db", "taille maximale occupée dans la base, ex. 1G, 512M (0 = sans limite ; défaut 1G)", sizeFlag(&cfg.MaxDB))
	flag.Func("min-free", "espace disque à laisser libre, ex. 2G (0 = sans limite ; défaut 2G)", sizeFlag(&cfg.MinFree))
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

	srv := NewServer(cfg, store)

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
	log.Printf("relais à l'écoute sur %s, empreinte SHA-256 : %s, base max %s, espace libre min %s",
		*listen, fingerprint(cert.Certificate[0]), human(cfg.MaxDB), human(cfg.MinFree))
	if err := httpSrv.ListenAndServeTLS("", ""); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatalf("serveur: %v", err)
		os.Exit(1)
	}
}
