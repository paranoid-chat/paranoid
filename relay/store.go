package main

import (
	"context"
	"crypto/rand"
	"database/sql"
	"errors"
	"path/filepath"

	_ "modernc.org/sqlite"
)

var (
	ErrNotFound = errors.New("boîte inconnue")
	ErrQuota    = errors.New("quota atteint")
)

type Message struct {
	Seq     uint64
	TS      int64
	Payload []byte
}

type Store struct {
	db  *sql.DB
	dir string
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

func OpenStore(path string) (*Store, error) {
	dsn := "file:" + path + "?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=synchronous(NORMAL)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, err
	}
	return &Store{db: db, dir: filepath.Dir(path)}, nil
}

func (s *Store) Close() error { return s.db.Close() }

func (s *Store) UsedBytes() (int64, error) {
	var pages, free, size int64
	if err := s.db.QueryRow("PRAGMA page_count").Scan(&pages); err != nil {
		return 0, err
	}
	if err := s.db.QueryRow("PRAGMA freelist_count").Scan(&free); err != nil {
		return 0, err
	}
	if err := s.db.QueryRow("PRAGMA page_size").Scan(&size); err != nil {
		return 0, err
	}
	return (pages - free) * size, nil
}

func (s *Store) FreeBytes() (int64, error) { return diskFree(s.dir) }

func randomID() ([]byte, error) {
	b := make([]byte, 16)
	_, err := rand.Read(b)
	return b, err
}

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

func (s *Store) PubKey(boxID []byte) ([]byte, error) {
	var pub []byte
	err := s.db.QueryRow(`SELECT pubkey FROM boxes WHERE box_id = ?`, boxID).Scan(&pub)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return pub, err
}

func (s *Store) WriteIDExists(writeID []byte) (bool, error) {
	var one int
	err := s.db.QueryRow(`SELECT 1 FROM boxes WHERE write_id = ?`, writeID).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return err == nil, err
}

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

func (s *Store) Touch(boxID []byte, now int64) error {
	_, err := s.db.Exec(`UPDATE boxes SET last_read = ? WHERE box_id = ?`, now, boxID)
	return err
}

func (s *Store) Ack(boxID []byte, upto uint64) error {
	_, err := s.db.Exec(`DELETE FROM msgs WHERE box_id = ? AND seq <= ?`, boxID, upto)
	return err
}

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
