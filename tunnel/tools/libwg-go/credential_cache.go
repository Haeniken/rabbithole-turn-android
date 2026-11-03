/* SPDX-License-Identifier: Apache-2.0 */
package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"time"
)

// Android supplies an encrypted, atomic store in noBackupFilesDir. Keeping
// serialization independent of JNI allows restart/crash behavior to be tested.
type credentialCacheBackend interface {
	Load(key string) ([]byte, error)
	Save(key string, value []byte) error
}

var defaultCredentialCache credentialCacheBackend

type credentialCacheEntry struct {
	Slot           int             `json:"slot"`
	Credentials    TurnCredentials `json:"credentials"`
	Active         int             `json:"active"`
	LastUsedAt     time.Time       `json:"last_used_at"`
	SaturatedUntil time.Time       `json:"saturated_until"`
}

type credentialCacheDocument struct {
	Version int                    `json:"version"`
	Key     string                 `json:"key"`
	Entries []credentialCacheEntry `json:"entries"`
}

func (p *credentialPool) enableCache(key string, backend credentialCacheBackend) {
	if backend == nil {
		return
	}
	hash := sha256.Sum256([]byte(key))
	p.cache, p.cacheKey = backend, hex.EncodeToString(hash[:])
	raw, err := backend.Load(p.cacheKey)
	if err != nil || len(raw) == 0 {
		return
	}
	var document credentialCacheDocument
	if len(raw) > 256*1024 || json.Unmarshal(raw, &document) != nil || document.Version != 1 || document.Key != p.cacheKey {
		return
	}
	now := time.Now()
	seen := make(map[string]bool)
	restored := 0
	for _, saved := range document.Entries {
		c := saved.Credentials
		expiry, ok := parseCredentialExpiry(c.Username)
		if !ok || c.Link != p.link || c.Password == "" || c.ServerAddr == "" || saved.Slot < 0 || saved.Slot >= len(p.entries) || seen[c.Username] {
			continue
		}
		// A stored timestamp can shorten validity, never extend VK's deadline.
		deadline := expiry.Add(-credentialExpiryBuffer)
		if c.ExpiresAt.After(deadline) {
			c.ExpiresAt = deadline
		}
		if !now.Before(c.ExpiresAt) {
			continue
		}
		entry := &p.entries[saved.Slot]
		if entry.creds.Username != "" {
			continue
		}
		seen[c.Username] = true
		restored++
		*entry = credentialPoolEntry{creds: c, validated: true, lastUsedAt: saved.LastUsedAt, saturatedUntil: saved.SaturatedUntil}
		if saved.Active > 0 {
			// The old process may have died without deleting its allocations.
			// Quarantine that identity until their relay lifetime has elapsed.
			until := now.Add(vkActiveAllocationsCooldown)
			if until.After(entry.saturatedUntil) {
				entry.saturatedUntil = until
			}
		}
	}
	if restored > 0 {
		credentialLog("[Auth] Restored %d verified credential slots from protected cache", restored)
	}
}

// Called under p.mu: writes must be ordered, especially invalidation versus a
// neighbouring stream's acquire. I/O failure only disables cross-run reuse;
// the live tunnel and in-memory pool continue to operate.
func (p *credentialPool) persistLocked() {
	if p.cache == nil {
		return
	}
	document := credentialCacheDocument{Version: 1, Key: p.cacheKey}
	now := time.Now()
	for slot, entry := range p.entries {
		if !entry.validated || !p.isFreshLocked(slot, now) {
			continue
		}
		if _, ok := parseCredentialExpiry(entry.creds.Username); !ok {
			continue
		}
		lastUsed := entry.lastUsedAt
		if entry.active > 0 {
			lastUsed = now
		}
		document.Entries = append(document.Entries, credentialCacheEntry{Slot: slot, Credentials: entry.creds, Active: entry.active, LastUsedAt: lastUsed, SaturatedUntil: entry.saturatedUntil})
	}
	raw, err := json.Marshal(document)
	if err != nil {
		p.cacheDirty = true
		return
	}
	if p.cache.Save(p.cacheKey, raw) != nil {
		p.cacheDirty = true
		credentialLog("[Auth] Credential cache could not be saved; continuing in memory")
	} else {
		p.cacheDirty = false
	}
}
