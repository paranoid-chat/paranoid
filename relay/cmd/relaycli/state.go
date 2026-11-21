package main

import (
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

type RelayRef struct {
	URL string `json:"url"`
	FP  []byte `json:"fp"`
}

type MyBox struct {
	Relay    RelayRef `json:"relay"`
	BoxID    []byte   `json:"box_id"`
	WriteID  []byte   `json:"write_id"`
	ReadSeed []byte   `json:"read_seed"`
	LastSeq  uint64   `json:"last_seq"`
}

type TheirBox struct {
	Relay   RelayRef `json:"relay"`
	WriteID []byte   `json:"write_id"`
}

type Contact struct {
	MyBox      *MyBox    `json:"my_box,omitempty"`
	IK         []byte    `json:"ik,omitempty"`
	XK         []byte    `json:"xk,omitempty"`
	Their      *TheirBox `json:"their,omitempty"`
	Seen       []string  `json:"seen,omitempty"`
	Session    *Session  `json:"session,omitempty"`
	Burned     bool      `json:"burned,omitempty"`
	PairSecret []byte    `json:"pair_secret,omitempty"`
}

func (s *State) ensureSession(c *Contact) (*drEnv, error) {
	e, err := newDREnv(s.IK(), s.X25519(), c.IK, c.XK)
	if err != nil {
		return nil, err
	}
	if c.Session == nil {
		if c.Session, err = NewSession(e); err != nil {
			return nil, err
		}
	}
	return e, nil
}

func (s *State) openBlob(c *Contact, blob []byte) (Plain, error) {
	if len(blob) != BlobSize {
		return Plain{}, ErrDecrypt
	}
	if blob[0] != drVersion {
		return Plain{}, ErrDRVersion
	}
	e, err := s.ensureSession(c)
	if err != nil {
		return Plain{}, err
	}
	inner, err := c.Session.Decrypt(e, blob)
	if err != nil {
		return Plain{}, err
	}
	return decodeInnerV2(inner)
}

type State struct {
	IdentitySeed []byte              `json:"identity_seed"`
	X25519Priv   []byte              `json:"x25519_priv"`
	Relay        RelayRef            `json:"relay"`
	Contacts     map[string]*Contact `json:"contacts"`

	path string
}

func (s *State) Identity() ed25519.PrivateKey { return ed25519.NewKeyFromSeed(s.IdentitySeed) }
func (s *State) IK() ed25519.PublicKey        { return s.Identity().Public().(ed25519.PublicKey) }

func (s *State) X25519() *ecdh.PrivateKey {
	k, err := ecdh.X25519().NewPrivateKey(s.X25519Priv)
	if err != nil {
		panic(err)
	}
	return k
}

func statePath(dir string) string { return filepath.Join(dir, "state.json") }

func LoadState(dir string) (*State, error) {
	p := statePath(dir)
	data, err := os.ReadFile(p)
	if errors.Is(err, os.ErrNotExist) {
		return nil, fmt.Errorf("aucune identité dans %s : lance d'abord « init »", dir)
	}
	if err != nil {
		return nil, err
	}
	var s State
	if err := json.Unmarshal(data, &s); err != nil {
		return nil, fmt.Errorf("state.json illisible : %w", err)
	}
	if len(s.IdentitySeed) != ed25519.SeedSize || len(s.X25519Priv) != 32 {
		return nil, errors.New("state.json : clés invalides")
	}
	if s.Contacts == nil {
		s.Contacts = map[string]*Contact{}
	}
	s.path = p
	return &s, nil
}

func (s *State) Save() error {
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

func SafetyNumber(ik []byte) string {
	sum := sha256.Sum256(ik)
	h := strings.ToUpper(hex.EncodeToString(sum[:10]))
	var parts []string
	for i := 0; i < len(h); i += 4 {
		parts = append(parts, h[i:i+4])
	}
	return strings.Join(parts, " ")
}

func ParseFingerprint(s string) ([]byte, error) {
	clean := strings.NewReplacer(":", "", " ", "").Replace(strings.TrimSpace(s))
	b, err := hex.DecodeString(clean)
	if err != nil || len(b) != 32 {
		return nil, errors.New("empreinte invalide : 32 octets attendus (format FB:2B:… ou hexadécimal)")
	}
	return b, nil
}

func FormatFingerprint(fp []byte) string {
	parts := make([]string, len(fp))
	for i, b := range fp {
		parts[i] = fmt.Sprintf("%02X", b)
	}
	return strings.Join(parts, ":")
}

type b64url []byte

func (b b64url) MarshalJSON() ([]byte, error) {
	return json.Marshal(base64.RawURLEncoding.EncodeToString(b))
}

func (b *b64url) UnmarshalJSON(data []byte) error {
	var s string
	if err := json.Unmarshal(data, &s); err != nil {
		return err
	}
	v, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		return err
	}
	*b = v
	return nil
}

type inviteRelay struct {
	U  string `json:"u"`
	FP b64url `json:"fp"`
	W  b64url `json:"w"`
}

type Invite struct {
	V  int           `json:"v"`
	IK b64url        `json:"ik"`
	XK b64url        `json:"xk"`
	R  []inviteRelay `json:"r"`
	S  b64url        `json:"s"`
}

func EncodeInvite(inv Invite) (string, error) {
	data, err := json.Marshal(inv)
	if err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(data), nil
}

func DecodeInvite(s string) (Invite, error) {
	var inv Invite
	data, err := base64.RawURLEncoding.DecodeString(strings.TrimSpace(s))
	if err != nil {
		return inv, errors.New("invitation illisible (base64url attendu)")
	}
	if err := json.Unmarshal(data, &inv); err != nil {
		return inv, errors.New("invitation illisible (JSON)")
	}
	if inv.V != 2 {
		return inv, fmt.Errorf("version d'invitation %d non prise en charge", inv.V)
	}
	if len(inv.IK) != ed25519.PublicKeySize || len(inv.XK) != 32 || len(inv.S) != SecretLen {
		return inv, errors.New("invitation : clés invalides")
	}
	if len(inv.R) == 0 || len(inv.R) > 3 {
		return inv, errors.New("invitation : 1 à 3 relais attendus")
	}
	for _, r := range inv.R {
		if !strings.HasPrefix(r.U, "https://") || len(r.FP) != 32 || len(r.W) != 16 {
			return inv, errors.New("invitation : relais invalide")
		}
	}
	return inv, nil
}
