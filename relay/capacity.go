package main

import (
	"sync"
	"syscall"
	"time"
)

type capacity struct {
	maxDB, minFree int64
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

func (c *capacity) canCreate(now time.Time) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.refresh(now)
	return !(c.maxDB > 0 && c.usedB*10 >= c.maxDB*9) && !c.diskLow()
}

func (c *capacity) canDeposit(now time.Time) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.refresh(now)
	return !(c.maxDB > 0 && c.usedB >= c.maxDB) && !c.diskLow()
}

func diskFree(dir string) (int64, error) {
	var st syscall.Statfs_t
	if err := syscall.Statfs(dir, &st); err != nil {
		return 0, err
	}
	return int64(st.Bavail) * int64(st.Bsize), nil
}
