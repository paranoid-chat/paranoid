package main

// Diagnostic : état de la session et en-têtes des blocs rejetés. N'affiche aucun secret
// (seulement les compteurs et le début des clés publiques).

import (
	"encoding/hex"
	"errors"
	"fmt"
)

func short(b []byte) string {
	if len(b) == 0 {
		return "-"
	}
	if len(b) > 4 {
		b = b[:4]
	}
	return hex.EncodeToString(b)
}

// blobInfo décrit l'en-tête en clair d'un bloc v2 (type de chaîne, clé DH, compteurs).
func blobInfo(blob []byte) string {
	if len(blob) != BlobSize || blob[0] != drVersion {
		return ""
	}
	h, err := parseDRHeader(blob)
	if err != nil {
		return ""
	}
	t := "ratchet"
	if h.Type == drTypeBoot {
		t = "démarrage"
	}
	return fmt.Sprintf(" [%s, clé %s, pn=%d, n=%d]", t, short(h.DH), h.PN, h.N)
}

func cmdDebug(s *State, args []string) error {
	if len(args) != 1 {
		return errors.New("usage : debug NOM")
	}
	c := s.Contacts[args[0]]
	if c == nil {
		return fmt.Errorf("contact %s inconnu", args[0])
	}
	fmt.Printf("contact %s : clés reçues=%v, ma boîte=%v, sa boîte=%v\n", args[0], c.IK != nil, c.MyBox != nil, c.Their != nil)
	if c.MyBox != nil {
		fmt.Printf("ma boîte : dernier n° relevé=%d\n", c.MyBox.LastSeq)
	}
	ss := c.Session
	if ss == nil {
		fmt.Println("session : aucune (créée au premier message)")
		return nil
	}
	role := "répondeur"
	if ss.Initiator {
		role = "initiateur"
	}
	pub := "-"
	if p, err := dhPub(ss.DHsPriv); err == nil {
		pub = short(p)
	}
	fmt.Printf("session : %s, ma clé DH %s, sa clé DH %s\n", role, pub, short(ss.DHr))
	fmt.Printf("envoi : chaîne=%v ns=%d pn=%d | réception : chaîne=%v nr=%d | clés sautées=%d\n",
		ss.CKs != nil, ss.Ns, ss.PN, ss.CKr != nil, ss.Nr, len(ss.Skipped))
	if ss.BootSend != nil {
		fmt.Printf("démarrage (envoi) : clé %s, n=%d\n", short(ss.BootSend.DH), ss.BootSend.N)
	}
	if ss.BootRecv != nil {
		fmt.Printf("démarrage (réception) : clé %s, n=%d\n", short(ss.BootRecv.DH), ss.BootRecv.N)
	}
	return nil
}
