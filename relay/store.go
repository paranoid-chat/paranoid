package main

import (
	"context"
	"crypto/rand"
	"database/sql"
	"errors"

	_ "modernc.org/sqlite"
)

var (
	ErrNotFound = errors.New("boîte inconnue")
	ErrQuota    = errors.New("quota atteint")
)

// Message est un enregistrement renvoyé à la relève.
type Message struct {
	Seq     uint64
	TS      int64
	Payload []byte
}

// Store encapsule la base SQLite. Les blobs sont opaques pour le relais.
type Store struct {
	db *sql.DB
}

const schema = `
CREATE TABLE IF NOT EXISTS boxes (
	box_id    BLOB PRIMARY KEY,
	write_id  BLOB NOT NULL UNIQUE,
	pubkey    BLOB NOT NULL,
	created   INTEGER NOT NULL,
	last_read INTEGER NOT NULL,
	next_seq  INTEGER NOT NULL DEFAULT 1
) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS msgs (
	box_id  BLOB NOT NULL,
	seq     INTEGER NOT NULL,
	ts      INTEGER NOT NULL,
	payload BLOB NOT NULL,
	PRIMARY KEY (box_id, seq)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS msgs_ts ON msgs(ts);
`

// OpenStore ouvre (ou crée) la base SQLite au chemin indiqué.
func OpenStore(path string) (*Store, error) {
	dsn := "file:" + path + "?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=synchronous(NORMAL)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	// Un seul accès simultané : simple et suffisant pour ce service.
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, err
	}
	if err := migratePush(db); err != nil {
		db.Close()
		return nil, err
	}
	return &Store{db: db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

func randomID() ([]byte, error) {
	b := make([]byte, 16)
	_, err := rand.Read(b)
	return b, err
}

// CreateBox crée une boîte et renvoie (box_id, write_id).
func (s *Store) CreateBox(pub []byte, now int64) (boxID, writeID []byte, err error) {
	if boxID, err = randomID(); err != nil {
		return nil, nil, err
	}
	if writeID, err = randomID(); err != nil {
		return nil, nil, err
	}
	_, err = s.db.Exec(
		`INSERT INTO boxes (box_id, write_id, pubkey, created, last_read) VALUES (?, ?, ?, ?, ?)`,
		boxID, writeID, pub, now, now)
	if err != nil {
		return nil, nil, err
	}
	return boxID, writeID, nil
}

// PubKey renvoie la clé publique de lecture d'une boîte.
func (s *Store) PubKey(boxID []byte) ([]byte, error) {
	var pub []byte
	err := s.db.QueryRow(`SELECT pubkey FROM boxes WHERE box_id = ?`, boxID).Scan(&pub)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return pub, err
}

// WriteIDExists indique si un write_id correspond à une boîte existante.
func (s *Store) WriteIDExists(writeID []byte) (bool, error) {
	var one int
	err := s.db.QueryRow(`SELECT 1 FROM boxes WHERE write_id = ?`, writeID).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return err == nil, err
}

// Deposit ajoute un message. Renvoie ErrNotFound ou ErrQuota le cas échéant.
func (s *Store) Deposit(writeID, payload []byte, now int64, quota int) (uint64, error) {
	tx, err := s.db.BeginTx(context.Background(), nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()

	var boxID []byte
	var next uint64
	err = tx.QueryRow(`SELECT box_id, next_seq FROM boxes WHERE write_id = ?`, writeID).Scan(&boxID, &next)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, ErrNotFound
	}
	if err != nil {
		return 0, err
	}
	var count int
	if err := tx.QueryRow(`SELECT COUNT(*) FROM msgs WHERE box_id = ?`, boxID).Scan(&count); err != nil {
		return 0, err
	}
	if count >= quota {
		return 0, ErrQuota
	}
	if _, err := tx.Exec(`INSERT INTO msgs (box_id, seq, ts, payload) VALUES (?, ?, ?, ?)`, boxID, next, now, payload); err != nil {
		return 0, err
	}
	if _, err := tx.Exec(`UPDATE boxes SET next_seq = next_seq + 1 WHERE box_id = ?`, boxID); err != nil {
		return 0, err
	}
	return next, tx.Commit()
}

// Fetch renvoie jusqu'à limit messages de séquence > after, et si d'autres restent.
func (s *Store) Fetch(boxID []byte, after uint64, limit int) ([]Message, bool, error) {
	rows, err := s.db.Query(
		`SELECT seq, ts, payload FROM msgs WHERE box_id = ? AND seq > ? ORDER BY seq LIMIT ?`,
		boxID, after, limit+1)
	if err != nil {
		return nil, false, err
	}
	defer rows.Close()
	var out []Message
	for rows.Next() {
		var m Message
		if err := rows.Scan(&m.Seq, &m.TS, &m.Payload); err != nil {
			return nil, false, err
		}
		out = append(out, m)
	}
	if err := rows.Err(); err != nil {
		return nil, false, err
	}
	more := len(out) > limit
	if more {
		out = out[:limit]
	}
	return out, more, nil
}

// Touch note une relève authentifiée (repousse l'expiration de la boîte).
func (s *Store) Touch(boxID []byte, now int64) error {
	_, err := s.db.Exec(`UPDATE boxes SET last_read = ? WHERE box_id = ?`, now, boxID)
	return err
}

// Ack supprime les messages de séquence <= upto.
func (s *Store) Ack(boxID []byte, upto uint64) error {
	_, err := s.db.Exec(`DELETE FROM msgs WHERE box_id = ? AND seq <= ?`, boxID, upto)
	return err
}

// DeleteBox supprime une boîte et ses messages.
func (s *Store) DeleteBox(boxID []byte) error {
	tx, err := s.db.BeginTx(context.Background(), nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`DELETE FROM msgs WHERE box_id = ?`, boxID); err != nil {
		return err
	}
	if _, err := tx.Exec(`DELETE FROM boxes WHERE box_id = ?`, boxID); err != nil {
		return err
	}
	return tx.Commit()
}

// Purge supprime les messages plus vieux que ttl et les boîtes sans relève depuis boxExpiry (secondes).
func (s *Store) Purge(now, ttl, boxExpiry int64) error {
	tx, err := s.db.BeginTx(context.Background(), nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`DELETE FROM msgs WHERE ts < ?`, now-ttl); err != nil {
		return err
	}
	if _, err := tx.Exec(`DELETE FROM msgs WHERE box_id IN (SELECT box_id FROM boxes WHERE last_read < ?)`, now-boxExpiry); err != nil {
		return err
	}
	if _, err := tx.Exec(`DELETE FROM boxes WHERE last_read < ?`, now-boxExpiry); err != nil {
		return err
	}
	return tx.Commit()
}

// ---------- notifications (UnifiedPush / Web Push) ----------

// PushTarget est le point de notification d'une boîte : URL Web Push et clés RFC 8291 du client.
type PushTarget struct {
	URL    string
	P256dh []byte // clé publique P-256 non compressée (65 octets)
	Auth   []byte // secret d'authentification (16 octets)
}

// migratePush ajoute les colonnes de notification aux bases créées avant leur introduction.
func migratePush(db *sql.DB) error {
	rows, err := db.Query(`PRAGMA table_info(boxes)`)
	if err != nil {
		return err
	}
	have := map[string]bool{}
	for rows.Next() {
		var cid, notnull, pk int
		var name, typ string
		var dflt sql.NullString
		if err := rows.Scan(&cid, &name, &typ, &notnull, &dflt, &pk); err != nil {
			rows.Close()
			return err
		}
		have[name] = true
	}
	rows.Close()
	for col, typ := range map[string]string{"push_url": "TEXT", "push_p256dh": "BLOB", "push_auth": "BLOB"} {
		if !have[col] {
			if _, err := db.Exec(`ALTER TABLE boxes ADD COLUMN ` + col + ` ` + typ); err != nil {
				return err
			}
		}
	}
	return nil
}

// SetPush enregistre (ou efface si t est nil) le point de notification d'une boîte.
func (s *Store) SetPush(boxID []byte, t *PushTarget) error {
	var res sql.Result
	var err error
	if t == nil {
		res, err = s.db.Exec(`UPDATE boxes SET push_url = NULL, push_p256dh = NULL, push_auth = NULL WHERE box_id = ?`, boxID)
	} else {
		res, err = s.db.Exec(`UPDATE boxes SET push_url = ?, push_p256dh = ?, push_auth = ? WHERE box_id = ?`, t.URL, t.P256dh, t.Auth, boxID)
	}
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// PushTarget renvoie le point de notification associé à un write_id, ou nil s'il n'y en a pas.
func (s *Store) PushTarget(writeID []byte) (*PushTarget, error) {
	var url sql.NullString
	var p256dh, auth []byte
	err := s.db.QueryRow(`SELECT push_url, push_p256dh, push_auth FROM boxes WHERE write_id = ?`, writeID).Scan(&url, &p256dh, &auth)
	if errors.Is(err, sql.ErrNoRows) || (err == nil && !url.Valid) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &PushTarget{URL: url.String, P256dh: p256dh, Auth: auth}, nil
}
