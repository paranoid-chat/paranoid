package main

// Client HTTP du relais (PROTOCOL.md §3 et §4), avec épinglage du certificat.

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

const recordSize = 16 + BlobSize

var (
	ErrQuotaFull = errors.New("la boîte du destinataire est pleine (100 messages en attente) : réessaie plus tard")
	ErrRelayFull = errors.New("le relais est plein : réessaie plus tard, ou préviens son administrateur")
)

type Record struct {
	Seq  uint64
	TS   int64
	Blob []byte
}

type RelayClient struct {
	base string
	http *http.Client
}

func NewRelayClient(r RelayRef) *RelayClient {
	fp := append([]byte(nil), r.FP...)
	tr := http.DefaultTransport.(*http.Transport).Clone()
	tr.ForceAttemptHTTP2 = true
	tr.TLSClientConfig = &tls.Config{
		MinVersion: tls.VersionTLS13,
		// Pas d'autorité de certification : on vérifie nous-mêmes l'empreinte du certificat.
		InsecureSkipVerify: true,
		VerifyConnection: func(cs tls.ConnectionState) error {
			if len(cs.PeerCertificates) == 0 {
				return errors.New("aucun certificat présenté par le relais")
			}
			sum := sha256.Sum256(cs.PeerCertificates[0].Raw)
			if subtle.ConstantTimeCompare(sum[:], fp) != 1 {
				return fmt.Errorf("empreinte du relais inattendue : %s", FormatFingerprint(sum[:]))
			}
			return nil
		},
	}
	return &RelayClient{
		base: strings.TrimRight(r.URL, "/"),
		http: &http.Client{Transport: tr, Timeout: 20 * time.Second},
	}
}

func enc(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

func (c *RelayClient) do(method, pathQuery string, body []byte, signer ed25519.PrivateKey) (*http.Response, []byte, error) {
	req, err := http.NewRequest(method, c.base+pathQuery, bytes.NewReader(body))
	if err != nil {
		return nil, nil, err
	}
	if signer != nil {
		ts := strconv.FormatInt(time.Now().Unix(), 10)
		nonce := make([]byte, 16)
		if _, err := rand.Read(nonce); err != nil {
			return nil, nil, err
		}
		n := enc(nonce)
		sum := sha256.Sum256(body)
		msg := method + "\n" + pathQuery + "\n" + ts + "\n" + n + "\n" + hex.EncodeToString(sum[:])
		req.Header.Set("X-Timestamp", ts)
		req.Header.Set("X-Nonce", n)
		req.Header.Set("X-Signature", enc(ed25519.Sign(signer, []byte(msg))))
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, nil, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	return resp, data, err
}

func statusErr(resp *http.Response, data []byte) error {
	if resp.Header.Get("X-Relay-Full") == "1" {
		return ErrRelayFull
	}
	if resp.StatusCode == http.StatusTooManyRequests {
		return fmt.Errorf("relais : trop de requêtes, réessaie dans %s s", resp.Header.Get("Retry-After"))
	}
	return fmt.Errorf("relais : %s (%s)", resp.Status, strings.TrimSpace(string(data)))
}

func (c *RelayClient) CreateBox(readPub ed25519.PublicKey) (boxID, writeID []byte, err error) {
	resp, data, err := c.do("POST", "/v1/box", readPub, nil)
	if err != nil {
		return nil, nil, err
	}
	if resp.StatusCode != http.StatusCreated || len(data) != 32 {
		return nil, nil, statusErr(resp, data)
	}
	return data[:16], data[16:], nil
}

func (c *RelayClient) Deposit(writeID, blob []byte) (uint64, error) {
	resp, data, err := c.do("PUT", "/v1/box/"+enc(writeID)+"/msg", blob, nil)
	if err != nil {
		return 0, err
	}
	if resp.StatusCode == http.StatusInsufficientStorage && resp.Header.Get("X-Relay-Full") != "1" {
		return 0, ErrQuotaFull
	}
	if resp.StatusCode != http.StatusCreated || len(data) != 8 {
		return 0, statusErr(resp, data)
	}
	return binary.BigEndian.Uint64(data), nil
}

func (c *RelayClient) Fetch(boxID []byte, key ed25519.PrivateKey, after uint64, limit int) ([]Record, bool, error) {
	pq := fmt.Sprintf("/v1/box/%s/msgs?after=%d&limit=%d", enc(boxID), after, limit)
	resp, data, err := c.do("GET", pq, nil, key)
	if err != nil {
		return nil, false, err
	}
	if resp.StatusCode != http.StatusOK || len(data)%recordSize != 0 {
		return nil, false, statusErr(resp, data)
	}
	var out []Record
	for i := 0; i < len(data); i += recordSize {
		r := data[i : i+recordSize]
		out = append(out, Record{
			Seq:  binary.BigEndian.Uint64(r[:8]),
			TS:   int64(binary.BigEndian.Uint64(r[8:16])),
			Blob: append([]byte(nil), r[16:]...),
		})
	}
	return out, resp.Header.Get("X-More") == "1", nil
}

func (c *RelayClient) Ack(boxID []byte, key ed25519.PrivateKey, upto uint64) error {
	resp, data, err := c.do("DELETE", fmt.Sprintf("/v1/box/%s/msgs?upto=%d", enc(boxID), upto), nil, key)
	if err != nil {
		return err
	}
	if resp.StatusCode != http.StatusNoContent {
		return statusErr(resp, data)
	}
	return nil
}

func (c *RelayClient) DeleteBox(boxID []byte, key ed25519.PrivateKey) error {
	resp, data, err := c.do("DELETE", "/v1/box/"+enc(boxID), nil, key)
	if err != nil {
		return err
	}
	if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusNotFound {
		return statusErr(resp, data)
	}
	return nil
}
