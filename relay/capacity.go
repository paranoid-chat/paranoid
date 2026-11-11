package main

// Plafond global (PROTOCOL.md §5) : taille maximale de la base (--max-db) et espace disque
// minimal à laisser libre (--min-free). Les mesures sont refaites au plus toutes les 10 s.
//
//   - créations de boîte refusées dès 90 % du plafond, ou sous l'espace libre minimal ;
//   - dépôts refusés à 100 % du plafond, ou sous l'espace libre minimal.
//
// On bloque d'abord les nouvelles boîtes : les conversations existantes passent le plus
// longtemps possible. Refus = 507 avec l'en-tête X-Relay-Full: 1.

import (
	"sync"
	"syscall"
	"time"
)

type capacity struct {
	maxDB, minFree int64 // 0 = contrôle désactivé
	used, free     func() (int64, error)
	every          time.Duration

	mu      sync.Mutex
	checked time.Time
	usedB   int64
	freeB   int64
}

func newCapacity(maxDB, minFree int64, used, free func() (int64, error)) *capacity {
	return &capacity{maxDB: maxDB, minFree: minFree, used: used, free: free, every: 10 * time.Second, freeB: -1}
}

// refresh remet les mesures à jour si elles ont plus de 10 s. En cas d'erreur de mesure, la
// valeur précédente est gardée : une panne de mesure ne bloque pas le relais.
func (c *capacity) refresh(now time.Time) {
	if !c.checked.IsZero() && now.Sub(c.checked) < c.every {
		return
	}
	c.checked = now
	if c.maxDB > 0 {
		if v, err := c.used(); err == nil {
			c.usedB = v
		}
	}
	if c.minFree > 0 {
		if v, err := c.free(); err == nil {
			c.freeB = v
		}
	}
}

func (c *capacity) diskLow() bool { return c.minFree > 0 && c.freeB >= 0 && c.freeB < c.minFree }

// canCreate : une nouvelle boîte est-elle acceptée ?
func (c *capacity) canCreate(now time.Time) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.refresh(now)
	return !(c.maxDB > 0 && c.usedB*10 >= c.maxDB*9) && !c.diskLow()
}

// canDeposit : un nouveau message est-il accepté ?
func (c *capacity) canDeposit(now time.Time) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.refresh(now)
	return !(c.maxDB > 0 && c.usedB >= c.maxDB) && !c.diskLow()
}

// diskFree renvoie l'espace disponible pour un utilisateur non root sur le système de fichiers de dir.
func diskFree(dir string) (int64, error) {
	var st syscall.Statfs_t
	if err := syscall.Statfs(dir, &st); err != nil {
		return 0, err
	}
	return int64(st.Bavail) * int64(st.Bsize), nil
}
