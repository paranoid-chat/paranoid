// relaycli : client de test en ligne de commande pour le relais (PROTOCOL.md) et le format de messages v1.
//
//	relaycli -d alice init --relay https://192.168.1.252:8443 --fp FB:2B:…
//	relaycli -d alice invite bob        → affiche l'invitation (contenu du QR)
//	relaycli -d bob   add alice <invitation>
//	relaycli -d bob   send alice "salut"
//	relaycli -d alice recv
package main

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/rand"
	"errors"
	"flag"
	"fmt"
	"os"
	"regexp"
	"sort"
	"strings"
	"time"
)

const maxSeen = 500

var nameRe = regexp.MustCompile(`^[a-z0-9_-]{1,32}$`)

func usage() {
	fmt.Fprint(os.Stderr, `Usage : relaycli [-d dossier] <commande> [arguments]

Commandes :
  init --relay URL --fp EMPREINTE   crée une identité et teste le relais
  whoami                            affiche ton empreinte de sécurité et ton relais
  invite [--qr] [--png F] NOM       crée une boîte pour NOM et affiche ton invitation (QR possible)
  add NOM INVITATION|-              enregistre le contact NOM (« - » : invitation lue sur l'entrée)
  send NOM TEXTE…                   envoie un message à NOM
  recv                              relève, affiche et acquitte les messages
  chat NOM                          discussion en direct avec NOM (relève automatique)
  contacts                          liste les contacts
  remove NOM                        supprime le contact et sa boîte sur le relais
  reset NOM                         réinitialise la session chiffrée avec NOM (à faire des deux côtés)

Le dossier vaut par défaut $RELAYCLI_DIR, sinon ./data.
`)
}

func main() {
	defDir := os.Getenv("RELAYCLI_DIR")
	if defDir == "" {
		defDir = "data"
	}
	dir := flag.String("d", defDir, "dossier de l'identité")
	flag.Usage = usage
	flag.Parse()
	args := flag.Args()
	if len(args) == 0 {
		usage()
		os.Exit(2)
	}
	var err error
	switch args[0] {
	case "init":
		err = cmdInit(*dir, args[1:])
	case "whoami":
		err = withState(*dir, cmdWhoami)
	case "invite":
		err = withState(*dir, func(s *State) error { return cmdInvite(s, args[1:]) })
	case "add":
		err = withState(*dir, func(s *State) error { return cmdAdd(s, args[1:]) })
	case "send":
		err = withState(*dir, func(s *State) error { return cmdSend(s, args[1:]) })
	case "recv":
		err = withState(*dir, cmdRecv)
	case "chat":
		err = withState(*dir, func(s *State) error { return cmdChat(s, args[1:]) })
	case "contacts":
		err = withState(*dir, cmdContacts)
	case "remove":
		err = withState(*dir, func(s *State) error { return cmdRemove(s, args[1:]) })
	case "reset":
		err = withState(*dir, func(s *State) error { return cmdReset(s, args[1:]) })
	case "help", "-h", "--help":
		usage()
	default:
		fmt.Fprintf(os.Stderr, "commande inconnue : %s\n\n", args[0])
		usage()
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "erreur :", err)
		os.Exit(1)
	}
}

func withState(dir string, f func(*State) error) error {
	s, err := LoadState(dir)
	if err != nil {
		return err
	}
	return f(s)
}

func checkName(n string) error {
	if !nameRe.MatchString(n) {
		return errors.New("nom de contact invalide (a-z, 0-9, _ et -, 32 caractères max)")
	}
	return nil
}

// ---------- init / whoami ----------

func cmdInit(dir string, args []string) error {
	fs := flag.NewFlagSet("init", flag.ContinueOnError)
	relayURL := fs.String("relay", "", "URL du relais, ex. https://192.168.1.252:8443")
	fpStr := fs.String("fp", "", "empreinte SHA-256 du certificat du relais")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if !strings.HasPrefix(*relayURL, "https://") {
		return errors.New("--relay doit commencer par https://")
	}
	fp, err := ParseFingerprint(*fpStr)
	if err != nil {
		return err
	}
	if _, err := os.Stat(statePath(dir)); err == nil {
		return fmt.Errorf("une identité existe déjà dans %s", dir)
	}
	ref := RelayRef{URL: strings.TrimRight(*relayURL, "/"), FP: fp}

	// Test de connexion : une boîte inconnue doit répondre 404, ce qui valide TLS et l'empreinte.
	cl := NewRelayClient(ref)
	resp, _, err := cl.do("GET", "/v1/box/"+enc(make([]byte, 16))+"/msgs", nil, nil)
	if err != nil {
		return fmt.Errorf("relais injoignable : %w", err)
	}
	if resp.StatusCode != 404 {
		return fmt.Errorf("réponse inattendue du relais : %s", resp.Status)
	}

	seed := make([]byte, ed25519.SeedSize)
	if _, err := rand.Read(seed); err != nil {
		return err
	}
	xk, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	s := &State{
		IdentitySeed: seed,
		X25519Priv:   xk.Bytes(),
		Relay:        ref,
		Contacts:     map[string]*Contact{},
		path:         statePath(dir),
	}
	if err := s.Save(); err != nil {
		return err
	}
	fmt.Println("Relais joignable, empreinte vérifiée.")
	fmt.Printf("Identité créée dans %s\nEmpreinte de sécurité : %s\n", dir, SafetyNumber(s.IK()))
	return nil
}

func cmdWhoami(s *State) error {
	fmt.Printf("Empreinte de sécurité : %s\nRelais : %s\nCertificat : %s\n",
		SafetyNumber(s.IK()), s.Relay.URL, FormatFingerprint(s.Relay.FP))
	return nil
}

// ---------- invite / add ----------

func cmdInvite(s *State, args []string) error {
	fs := flag.NewFlagSet("invite", flag.ContinueOnError)
	showQR := fs.Bool("qr", false, "affiche le QR dans le terminal")
	light := fs.Bool("light", false, "terminal à fond clair (avec --qr)")
	png := fs.String("png", "", "enregistre le QR dans ce fichier PNG")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 1 {
		return errors.New("usage : invite [--qr] [--light] [--png FICHIER] NOM")
	}
	name := fs.Arg(0)
	if err := checkName(name); err != nil {
		return err
	}
	c := s.Contacts[name]
	if c == nil {
		c = &Contact{}
	}
	if c.MyBox == nil {
		readPub, readPriv, err := ed25519.GenerateKey(rand.Reader)
		if err != nil {
			return err
		}
		boxID, writeID, err := NewRelayClient(s.Relay).CreateBox(readPub)
		if err != nil {
			return err
		}
		c.MyBox = &MyBox{Relay: s.Relay, BoxID: boxID, WriteID: writeID, ReadSeed: readPriv.Seed()}
		s.Contacts[name] = c
		if err := s.Save(); err != nil {
			return err
		}
	}
	inv, err := EncodeInvite(Invite{
		V:  1,
		IK: b64url(s.IK()),
		XK: s.X25519().PublicKey().Bytes(),
		R:  []inviteRelay{{U: c.MyBox.Relay.URL, FP: c.MyBox.Relay.FP, W: c.MyBox.WriteID}},
	})
	if err != nil {
		return err
	}
	if *showQR {
		q, err := terminalQR(inv, !*light)
		if err != nil {
			return err
		}
		fmt.Fprintln(os.Stderr, q)
	}
	if *png != "" {
		if err := writeQRPNG(inv, *png); err != nil {
			return err
		}
		fmt.Fprintf(os.Stderr, "QR enregistré dans %s\n", *png)
	}
	fmt.Fprintf(os.Stderr, "Invitation pour %s (à transmettre uniquement à %s) :\n", name, name)
	fmt.Println(inv)
	fmt.Fprintf(os.Stderr, "Ton empreinte de sécurité : %s\n", SafetyNumber(s.IK()))
	return nil
}

func cmdAdd(s *State, args []string) error {
	if len(args) != 2 {
		return errors.New("usage : add NOM INVITATION")
	}
	name := args[0]
	var err error
	if err = checkName(name); err != nil {
		return err
	}
	raw := args[1]
	if raw == "-" {
		if raw, err = readAllStdin(); err != nil {
			return err
		}
	}
	inv, err := DecodeInvite(raw)
	if err != nil {
		return err
	}
	if bytes.Equal(inv.IK, s.IK()) {
		return errors.New("c'est ta propre invitation")
	}
	for other, oc := range s.Contacts {
		if other != name && bytes.Equal(oc.IK, inv.IK) {
			return fmt.Errorf("cette identité est déjà enregistrée sous le nom %s", other)
		}
	}
	c := s.Contacts[name]
	if c == nil {
		c = &Contact{}
	}
	if c.IK != nil && !bytes.Equal(c.IK, inv.IK) {
		return fmt.Errorf("les clés de %s ont changé : vérifie de vive voix, puis « remove %s » avant de l'ajouter à nouveau", name, name)
	}
	r := inv.R[0]
	c.IK, c.XK = inv.IK, inv.XK
	c.Their = &TheirBox{Relay: RelayRef{URL: r.U, FP: r.FP}, WriteID: r.W}
	s.Contacts[name] = c
	if err := s.Save(); err != nil {
		return err
	}
	fmt.Printf("Contact %s enregistré.\nEmpreinte de sécurité de %s : %s\n", name, name, SafetyNumber(c.IK))
	fmt.Println("Compare-la avec celle que " + name + " lit dans « whoami ».")
	if c.MyBox == nil {
		fmt.Printf("Il te reste à lui donner ton invitation : relaycli invite %s\n", name)
	}
	return nil
}

// ---------- send / recv ----------

func cmdSend(s *State, args []string) error {
	if len(args) < 2 {
		return errors.New("usage : send NOM TEXTE…")
	}
	seq, err := sendMessage(s, args[0], strings.Join(args[1:], " "))
	if err != nil {
		return err
	}
	fmt.Printf("Envoyé à %s (n° %d sur le relais).\n", args[0], seq)
	return nil
}

// sendMessage chiffre et dépose un message ; renvoie le numéro attribué par le relais.
func sendMessage(s *State, name, msg string) (uint64, error) {
	c := s.Contacts[name]
	if c == nil || c.Their == nil || c.IK == nil {
		return 0, fmt.Errorf("contact %s inconnu : enregistre d'abord son invitation avec « add »", name)
	}
	text := []byte(msg)
	if len(text) > MaxTextV2 {
		return 0, fmt.Errorf("message trop long (%d octets, max %d)", len(text), MaxTextV2)
	}
	p := Plain{Type: TypeText, TS: time.Now().Unix(), Body: text}
	if _, err := rand.Read(p.ID[:]); err != nil {
		return 0, err
	}
	e, err := s.ensureSession(c)
	if err != nil {
		return 0, err
	}
	inner, err := encodeInnerV2(p)
	if err != nil {
		return 0, err
	}
	blob, err := c.Session.Encrypt(e, inner)
	if err != nil {
		return 0, err
	}
	// La session est enregistrée avant le dépôt : une clé de message n'est jamais réutilisée,
	// même si le dépôt échoue (le destinataire verra simplement un message manquant).
	if err := s.Save(); err != nil {
		return 0, err
	}
	return NewRelayClient(c.Their.Relay).Deposit(c.Their.WriteID, blob)
}

func (c *Contact) seen(id string) bool {
	for _, v := range c.Seen {
		if v == id {
			return true
		}
	}
	return false
}

func (c *Contact) markSeen(id string) {
	c.Seen = append(c.Seen, id)
	if len(c.Seen) > maxSeen {
		c.Seen = c.Seen[len(c.Seen)-maxSeen:]
	}
}

func sortedNames(s *State) []string {
	names := make([]string, 0, len(s.Contacts))
	for n := range s.Contacts {
		names = append(names, n)
	}
	sort.Strings(names)
	return names
}

func cmdRecv(s *State) error {
	total := 0
	var errs []string
	for _, name := range sortedNames(s) {
		n, err := recvFrom(s, name)
		total += n
		if err != nil {
			errs = append(errs, fmt.Sprintf("%s : %v", name, err))
		}
	}
	if total == 0 && len(errs) == 0 {
		fmt.Println("Aucun nouveau message.")
	}
	if len(errs) > 0 {
		return errors.New(strings.Join(errs, " ; "))
	}
	return nil
}

func recvFrom(s *State, name string) (int, error) {
	c := s.Contacts[name]
	if c.MyBox == nil {
		return 0, nil
	}
	cl := NewRelayClient(c.MyBox.Relay)
	key := ed25519.NewKeyFromSeed(c.MyBox.ReadSeed)

	if c.IK == nil {
		// Sans les clés du contact, on ne peut pas vérifier l'expéditeur : on n'acquitte rien.
		recs, more, err := cl.Fetch(c.MyBox.BoxID, key, c.MyBox.LastSeq, 50)
		if err != nil {
			return 0, err
		}
		if len(recs) > 0 {
			plus := ""
			if more {
				plus = "+"
			}
			fmt.Printf("%d%s message(s) de %s en attente : enregistre d'abord son invitation avec « add %s ».\n", len(recs), plus, name, name)
		}
		return 0, nil
	}

	shown := 0
	for {
		recs, more, err := cl.Fetch(c.MyBox.BoxID, key, c.MyBox.LastSeq, 50)
		if err != nil {
			return shown, err
		}
		if len(recs) == 0 {
			return shown, nil
		}
		for _, r := range recs {
			p, err := s.openBlob(c, r.Blob)
			switch {
			case err != nil:
				fmt.Printf("[%s] message n° %d rejeté : %v\n", name, r.Seq, err)
			case p.Type != TypeText:
				fmt.Printf("[%s] message n° %d de type %d ignoré\n", name, r.Seq, p.Type)
			default:
				id := enc(p.ID[:])
				if c.seen(id) {
					break
				}
				c.markSeen(id)
				when := time.Unix(p.TS, 0).Local().Format("02/01 15:04:05")
				fmt.Printf("[%s] %s : %s\n", when, name, p.Body)
				shown++
			}
			c.MyBox.LastSeq = r.Seq
		}
		// On enregistre avant d'acquitter : en cas d'échec de l'acquittement, rien n'est perdu.
		if err := s.Save(); err != nil {
			return shown, err
		}
		if err := cl.Ack(c.MyBox.BoxID, key, c.MyBox.LastSeq); err != nil {
			return shown, err
		}
		if !more {
			return shown, nil
		}
	}
}

// ---------- contacts / remove ----------

func cmdContacts(s *State) error {
	if len(s.Contacts) == 0 {
		fmt.Println("Aucun contact.")
		return nil
	}
	for _, name := range sortedNames(s) {
		c := s.Contacts[name]
		var status []string
		if c.IK != nil {
			status = append(status, "empreinte "+SafetyNumber(c.IK))
		} else {
			status = append(status, "invitation non reçue (add)")
		}
		if c.MyBox == nil {
			status = append(status, "invitation non donnée (invite)")
		}
		fmt.Printf("%-16s %s\n", name, strings.Join(status, ", "))
	}
	return nil
}

func cmdReset(s *State, args []string) error {
	if len(args) != 1 {
		return errors.New("usage : reset NOM")
	}
	c := s.Contacts[args[0]]
	if c == nil {
		return fmt.Errorf("contact %s inconnu", args[0])
	}
	c.Session = nil
	if err := s.Save(); err != nil {
		return err
	}
	fmt.Printf("Session avec %s réinitialisée. %s doit faire « reset » de son côté aussi.\n", args[0], args[0])
	return nil
}

func cmdRemove(s *State, args []string) error {
	if len(args) != 1 {
		return errors.New("usage : remove NOM")
	}
	name := args[0]
	c := s.Contacts[name]
	if c == nil {
		return fmt.Errorf("contact %s inconnu", name)
	}
	if c.MyBox != nil {
		key := ed25519.NewKeyFromSeed(c.MyBox.ReadSeed)
		if err := NewRelayClient(c.MyBox.Relay).DeleteBox(c.MyBox.BoxID, key); err != nil {
			return fmt.Errorf("suppression de la boîte sur le relais : %w", err)
		}
	}
	delete(s.Contacts, name)
	if err := s.Save(); err != nil {
		return err
	}
	fmt.Printf("Contact %s supprimé (et sa boîte sur le relais).\n", name)
	return nil
}
