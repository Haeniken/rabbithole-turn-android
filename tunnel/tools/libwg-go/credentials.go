/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 WireGuard LLC. All Rights Reserved.
 */

package main

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// TurnCredentials stores one independently-issued TURN credential set.
type TurnCredentials struct {
	Username   string
	Password   string
	ServerAddr string
	ExpiresAt  time.Time
	Link       string
}

const (
	credentialLifetime = 10 * time.Minute
	cacheSafetyMargin  = 60 * time.Second
	maxCacheErrors     = 3
	errorWindow        = 10 * time.Second

	// VK TURN currently accepts at most ten simultaneous allocations for one
	// credential set and returns 486 Allocation Quota Reached for the rest.
	maxStreamsPerCred = 10

	credentialFetchCooldownMin  = 5 * time.Second
	credentialFetchCooldownMax  = time.Minute
	vkSaturationCooldown        = 3 * time.Minute
	vkActiveAllocationsCooldown = 11 * time.Minute
	activeAllocationsWindow     = 12 * time.Minute
)

func normalizeStreamsPerCred(requested int) int {
	if requested < 1 {
		return 1
	}
	if requested > maxStreamsPerCred {
		return maxStreamsPerCred
	}
	return requested
}

// credentialLog is replaced with Android's logger by turn-client.go. Keeping
// the pool independent from cgo makes its scheduling and quota logic directly
// unit-testable on the host.
var credentialLog = func(string, ...interface{}) {}

// fetchFunc retrieves a fresh credential set without applying pool policy.
type fetchFunc func(ctx context.Context, link string) (string, string, string, error)

// VK rate-limits credential minting. Fetches for different slots therefore
// remain serialized, while acquisition from already-filled slots stays fully
// concurrent.
var fetchMu sync.Mutex

func serializeFetch(ctx context.Context, link string, storeFn fetchFunc) (string, string, string, error) {
	fetchMu.Lock()
	defer fetchMu.Unlock()
	if err := ctx.Err(); err != nil {
		return "", "", "", err
	}
	return storeFn(ctx, link)
}

type credentialPoolEntry struct {
	creds TurnCredentials

	active        int
	fetching      bool
	fetchFailures int

	lastUsedAt     time.Time
	cooldownUntil  time.Time
	saturatedUntil time.Time

	authErrors    int
	lastAuthError time.Time
}

// credentialPool groups streams onto credential sets up to the real TURN
// allocation quota and retains one extra reserve slot. A stream prefers its
// own group but may use any healthy slot with free quota.
type credentialPool struct {
	mu sync.Mutex

	link      string
	perSlot   int
	baseSlots int
	entries   []credentialPoolEntry
	fetch     fetchFunc
}

type credentialLease struct {
	pool     *credentialPool
	slot     int
	creds    TurnCredentials
	released atomic.Bool
}

func (l *credentialLease) release() {
	if l == nil || l.pool == nil || !l.released.CompareAndSwap(false, true) {
		return
	}
	l.pool.release(l.slot)
}

func (l *credentialLease) recordAuthError() bool {
	if l == nil || l.pool == nil {
		return false
	}
	return l.pool.recordAuthError(l.slot)
}

func (l *credentialLease) markSaturated() time.Duration {
	if l == nil || l.pool == nil {
		return 0
	}
	return l.pool.markSaturated(l.slot)
}

type credentialPoolUnavailableError struct {
	reason     string
	retryAfter time.Duration
}

func (e *credentialPoolUnavailableError) Error() string {
	return fmt.Sprintf("credential pool unavailable: %s", e.reason)
}

func (e *credentialPoolUnavailableError) RetryAfter() time.Duration {
	return e.retryAfter
}

type retryAfterError interface {
	RetryAfter() time.Duration
}

func retryAfterFromError(err error) time.Duration {
	var retryErr retryAfterError
	if errors.As(err, &retryErr) {
		return retryErr.RetryAfter()
	}
	return 0
}

func credentialPoolSize(totalStreams, perSlot int) (base, total int) {
	if totalStreams < 1 {
		totalStreams = 1
	}
	perSlot = normalizeStreamsPerCred(perSlot)
	base = (totalStreams + perSlot - 1) / perSlot
	// One independently-minted reserve lets reconnects recover while a used
	// credential is cooling down on the TURN side.
	return base, base + 1
}

func newCredentialPool(link string, totalStreams, perSlot int, fetch fetchFunc) *credentialPool {
	base, total := credentialPoolSize(totalStreams, perSlot)
	return &credentialPool{
		link:      link,
		perSlot:   normalizeStreamsPerCred(perSlot),
		baseSlots: base,
		entries:   make([]credentialPoolEntry, total),
		fetch:     fetch,
	}
}

func (p *credentialPool) isFreshLocked(slot int, now time.Time) bool {
	if slot < 0 || slot >= len(p.entries) {
		return false
	}
	e := &p.entries[slot]
	return e.creds.Link == p.link && e.creds.Username != "" && now.Before(e.creds.ExpiresAt)
}

// pickFreshLocked prefers affinity, then compact-fills the most-used healthy
// slot. Compact fill consumes fewer credential sets during steady state and
// leaves the reserve genuinely unused for recovery.
func (p *credentialPool) pickFreshLocked(streamID int, now time.Time) int {
	own := streamID / p.perSlot
	if own < 0 {
		own = 0
	}
	if own >= p.baseSlots {
		own = p.baseSlots - 1
	}
	if p.isFreshLocked(own, now) && !now.Before(p.entries[own].saturatedUntil) && p.entries[own].active < p.perSlot {
		return own
	}

	best, bestActive := -1, -1
	for i := range p.entries {
		e := &p.entries[i]
		if !p.isFreshLocked(i, now) || now.Before(e.saturatedUntil) || e.active >= p.perSlot {
			continue
		}
		if e.active > bestActive {
			best, bestActive = i, e.active
		}
	}
	return best
}

func (p *credentialPool) earliestRetryLocked(now time.Time) time.Duration {
	retry := time.Second
	for i := range p.entries {
		for _, until := range []time.Time{p.entries[i].cooldownUntil, p.entries[i].saturatedUntil} {
			if until.After(now) {
				remaining := until.Sub(now)
				if retry == time.Second || remaining < retry {
					retry = remaining
				}
			}
		}
	}
	if retry < 250*time.Millisecond {
		return 250 * time.Millisecond
	}
	if retry > 30*time.Second {
		return 30 * time.Second
	}
	return retry
}

// pickFetchTargetLocked only provisions the stream's base slot during a cold
// start. The reserve is filled lazily after a real failure/saturation, avoiding
// the startup credential burst that used to trigger quota cascades.
func (p *credentialPool) pickFetchTargetLocked(streamID int, now time.Time) int {
	own := streamID / p.perSlot
	if own < 0 {
		own = 0
	}
	if own >= p.baseSlots {
		own = p.baseSlots - 1
	}
	e := &p.entries[own]
	ownBlocked := e.fetching || now.Before(e.cooldownUntil)
	ownSaturated := now.Before(e.saturatedUntil)
	ownReplaceable := e.active == 0 && (!p.isFreshLocked(own, now) || ownSaturated)
	if ownReplaceable && !ownBlocked {
		return own
	}

	// If the owner is merely being fetched by another stream, park and share
	// that result. A reserve fetch in this case would defeat compact fill.
	if e.fetching {
		return -1
	}

	// Reserve slots are recovery capacity, not cold-start capacity.
	if ownSaturated || now.Before(e.cooldownUntil) || (e.active > 0 && !p.isFreshLocked(own, now)) {
		for i := p.baseSlots; i < len(p.entries); i++ {
			candidate := &p.entries[i]
			if candidate.active == 0 && !candidate.fetching && !now.Before(candidate.cooldownUntil) &&
				(!p.isFreshLocked(i, now) || now.Before(candidate.saturatedUntil)) {
				return i
			}
		}
	}
	return -1
}

func (p *credentialPool) acquire(ctx context.Context, streamID int) (*credentialLease, error) {
	for {
		if err := ctx.Err(); err != nil {
			return nil, err
		}

		now := time.Now()
		p.mu.Lock()
		if slot := p.pickFreshLocked(streamID, now); slot >= 0 {
			p.entries[slot].active++
			creds := p.entries[slot].creds
			active := p.entries[slot].active
			p.mu.Unlock()
			credentialLog("[Auth] Stream %d acquired credential slot %d (active=%d/%d)", streamID, slot, active, p.perSlot)
			return &credentialLease{pool: p, slot: slot, creds: creds}, nil
		}

		target := p.pickFetchTargetLocked(streamID, now)
		if target < 0 {
			retry := p.earliestRetryLocked(now)
			p.mu.Unlock()
			return nil, &credentialPoolUnavailableError{reason: "all usable slots are busy, fetching, or cooling down", retryAfter: retry}
		}
		p.entries[target].fetching = true
		p.mu.Unlock()

		credentialLog("[Auth] Fetching fresh credentials for slot %d", target)
		user, pass, addr, err := serializeFetch(ctx, p.link, p.fetch)

		p.mu.Lock()
		entry := &p.entries[target]
		entry.fetching = false
		if err != nil {
			entry.fetchFailures++
			shift := entry.fetchFailures - 1
			if shift > 4 {
				shift = 4
			}
			cooldown := credentialFetchCooldownMin * time.Duration(1<<shift)
			if cooldown > credentialFetchCooldownMax {
				cooldown = credentialFetchCooldownMax
			}
			entry.cooldownUntil = time.Now().Add(cooldown)
			p.mu.Unlock()
			return nil, fmt.Errorf("credential fetch for slot %d failed: %w", target, err)
		}

		*entry = credentialPoolEntry{
			creds: TurnCredentials{
				Username:   user,
				Password:   pass,
				ServerAddr: addr,
				ExpiresAt:  time.Now().Add(credentialLifetime - cacheSafetyMargin),
				Link:       p.link,
			},
		}
		p.mu.Unlock()
		credentialLog("[Auth] Credential slot %d filled; reserve-aware pool is ready", target)
		// Loop through the common selection path so active accounting and
		// affinity are identical for cached and freshly fetched credentials.
	}
}

func (p *credentialPool) release(slot int) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if slot < 0 || slot >= len(p.entries) {
		return
	}
	if p.entries[slot].active > 0 {
		p.entries[slot].active--
		if p.entries[slot].active == 0 {
			p.entries[slot].lastUsedAt = time.Now()
		}
	}
}

func (p *credentialPool) recordAuthError(slot int) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	if slot < 0 || slot >= len(p.entries) {
		return false
	}
	e := &p.entries[slot]
	now := time.Now()
	if e.lastAuthError.IsZero() || now.Sub(e.lastAuthError) > errorWindow {
		e.authErrors = 0
	}
	e.authErrors++
	e.lastAuthError = now
	credentialLog("[Auth] Slot %d authentication error (%d/%d)", slot, e.authErrors, maxCacheErrors)
	if e.authErrors < maxCacheErrors {
		return false
	}
	// Existing allocations retain their copied credentials. New acquisitions
	// stop using this entry and refill it as soon as active reaches zero.
	e.creds.ExpiresAt = time.Time{}
	e.authErrors = 0
	e.lastAuthError = time.Time{}
	credentialLog("[Auth] Slot %d invalidated after repeated authentication failures", slot)
	return true
}

func isAllocationQuotaError(err error) bool {
	if err == nil {
		return false
	}
	s := strings.ToLower(err.Error())
	return strings.Contains(s, "486") || strings.Contains(s, "allocation quota") || strings.Contains(s, "quota reached")
}

func isAuthError(err error) bool {
	if err == nil {
		return false
	}
	s := strings.ToLower(err.Error())
	return strings.Contains(s, "401") ||
		strings.Contains(s, "unauthorized") ||
		strings.Contains(s, "authentication") ||
		strings.Contains(s, "invalid credential") ||
		strings.Contains(s, "stale nonce")
}

func (p *credentialPool) markSaturated(slot int) time.Duration {
	p.mu.Lock()
	defer p.mu.Unlock()
	if slot < 0 || slot >= len(p.entries) {
		return 0
	}
	e := &p.entries[slot]
	cooldown := vkSaturationCooldown
	reason := "residual allocation quota"
	if e.active > 0 || (!e.lastUsedAt.IsZero() && time.Since(e.lastUsedAt) < activeAllocationsWindow) {
		cooldown = vkActiveAllocationsCooldown
		reason = "recent allocations may still occupy the TURN quota"
	}
	until := time.Now().Add(cooldown)
	if until.After(e.saturatedUntil) {
		e.saturatedUntil = until
	}
	credentialLog("[Auth] Slot %d cooling down for %v after 486 (%s)", slot, cooldown, reason)
	return cooldown
}

func (p *credentialPool) markActiveForNetworkChange() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	marked := 0
	for i := range p.entries {
		e := &p.entries[i]
		if e.active == 0 {
			continue
		}
		until := time.Now().Add(vkActiveAllocationsCooldown)
		if until.After(e.saturatedUntil) {
			e.saturatedUntil = until
		}
		marked++
	}
	return marked
}

func (p *credentialPool) snapshot() (fresh, active, saturated, total int) {
	p.mu.Lock()
	defer p.mu.Unlock()
	now := time.Now()
	total = len(p.entries)
	for i := range p.entries {
		if p.isFreshLocked(i, now) {
			fresh++
		}
		active += p.entries[i].active
		if now.Before(p.entries[i].saturatedUntil) {
			saturated++
		}
	}
	return
}

var credentialPools = struct {
	mu    sync.Mutex
	pools map[string]*credentialPool
}{pools: make(map[string]*credentialPool)}

func getCredentialPool(key, link string, totalStreams, perSlot int, fetch fetchFunc) *credentialPool {
	credentialPools.mu.Lock()
	defer credentialPools.mu.Unlock()
	if existing := credentialPools.pools[key]; existing != nil {
		return existing
	}
	pool := newCredentialPool(link, totalStreams, perSlot, fetch)
	credentialPools.pools[key] = pool
	return pool
}

func markCredentialPoolsForNetworkChange() int {
	credentialPools.mu.Lock()
	pools := make([]*credentialPool, 0, len(credentialPools.pools))
	for _, pool := range credentialPools.pools {
		pools = append(pools, pool)
	}
	credentialPools.mu.Unlock()
	marked := 0
	for _, pool := range pools {
		marked += pool.markActiveForNetworkChange()
	}
	return marked
}

// getCredsFunc is the mode-independent entry point used by stream.run.
type getCredsFunc func(context.Context, int) (*credentialLease, error)
