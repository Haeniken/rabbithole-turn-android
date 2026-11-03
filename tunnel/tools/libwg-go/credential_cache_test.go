/* SPDX-License-Identifier: Apache-2.0 */
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type memoryCredentialCache struct {
	mu     sync.Mutex
	data   map[string][]byte
	fail   bool
	writes int
}

func (m *memoryCredentialCache) Load(key string) ([]byte, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return append([]byte(nil), m.data[key]...), nil
}
func (m *memoryCredentialCache) Save(key string, value []byte) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.writes++
	if m.fail {
		return errors.New("store unavailable")
	}
	if m.data == nil {
		m.data = make(map[string][]byte)
	}
	m.data[key] = append([]byte(nil), value...)
	return nil
}
func testCredentialFetcher(count *atomic.Int32) fetchFunc {
	return func(context.Context, string) (string, string, string, error) {
		n := count.Add(1)
		return fmt.Sprintf("%d:key-%d", time.Now().Add(8*time.Hour).Unix(), n), "password", "127.0.0.1:3478", nil
	}
}
func acquireTestLease(t *testing.T, p *credentialPool, id int) *credentialLease {
	t.Helper()
	l, e := p.acquire(context.Background(), id)
	if e != nil {
		t.Fatal(e)
	}
	return l
}

func TestCredentialExpiryUsesVKTimestamp(t *testing.T) {
	now := time.Now().Truncate(time.Second)
	for _, tc := range []struct {
		user string
		want time.Time
	}{
		{fmt.Sprintf("%d:key", now.Add(8*time.Hour).Unix()), now.Add(7*time.Hour + 30*time.Minute)},
		{"legacy-name", now.Add(9 * time.Minute)},
		{"nope:key", now.Add(9 * time.Minute)},
		{"-5:key", now.Add(9 * time.Minute)},
	} {
		if got := credentialUsableUntil(tc.user, now); !got.Equal(tc.want) {
			t.Fatalf("deadline=%v want %v", got, tc.want)
		}
	}
}

func TestNearExpiredFreshMintDoesNotLoop(t *testing.T) {
	var calls atomic.Int32
	p := newCredentialPool("link", 1, 1, func(context.Context, string) (string, string, string, error) {
		calls.Add(1)
		return fmt.Sprintf("%d:key", time.Now().Add(20*time.Minute).Unix()), "pass", "relay", nil
	})
	if _, err := p.acquire(context.Background(), 0); err == nil {
		t.Fatal("accepted near-expired mint")
	}
	if calls.Load() != 1 {
		t.Fatal("repeated expired mint instead of backing off")
	}
}

func TestValidatedCredentialSurvivesRestartWithoutMint(t *testing.T) {
	var calls atomic.Int32
	store := &memoryCredentialCache{}
	p := newCredentialPool("link", 4, 4, testCredentialFetcher(&calls))
	p.enableCache("pool", store)
	l := acquireTestLease(t, p, 0)
	l.markValidated()
	l.release()
	restarted := newCredentialPool("link", 4, 4, testCredentialFetcher(&calls))
	restarted.enableCache("pool", store)
	next := acquireTestLease(t, restarted, 0)
	defer next.release()
	if calls.Load() != 1 || next.creds.Username != l.creds.Username {
		t.Fatal("cached credential was not reused")
	}
	if time.Until(next.creds.ExpiresAt) < 7*time.Hour {
		t.Fatal("VK lifetime shortened to old cache interval")
	}
}

func TestUnvalidatedCredentialsAreNotPersisted(t *testing.T) {
	var calls atomic.Int32
	store := &memoryCredentialCache{}
	p := newCredentialPool("link", 1, 1, testCredentialFetcher(&calls))
	p.enableCache("pool", store)
	l := acquireTestLease(t, p, 0)
	l.release()
	r := newCredentialPool("link", 1, 1, testCredentialFetcher(&calls))
	r.enableCache("pool", store)
	next := acquireTestLease(t, r, 0)
	next.release()
	if calls.Load() != 2 {
		t.Fatal("persisted credentials without a successful allocation")
	}
}

func TestAuthRejectionRemovesCredentialFromDisk(t *testing.T) {
	var calls atomic.Int32
	store := &memoryCredentialCache{}
	p := newCredentialPool("link", 1, 1, testCredentialFetcher(&calls))
	p.enableCache("pool", store)
	l := acquireTestLease(t, p, 0)
	l.markValidated()
	if !l.recordAuthError() {
		t.Fatal("a rejected verified identity remained available for slow reconnects")
	}
	l.release()
	r := newCredentialPool("link", 1, 1, testCredentialFetcher(&calls))
	r.enableCache("pool", store)
	next := acquireTestLease(t, r, 0)
	next.release()
	if calls.Load() != 2 {
		t.Fatal("revoked credential survived process restart")
	}
}

func TestCrashQuarantinesPossiblyLiveAllocations(t *testing.T) {
	var calls atomic.Int32
	store := &memoryCredentialCache{}
	p := newCredentialPool("link", 1, 1, testCredentialFetcher(&calls))
	p.enableCache("pool", store)
	l := acquireTestLease(t, p, 0)
	l.markValidated()
	// The process dies without releasing its lease. Last use may be hours
	// ago, while the relay continued refreshing; quarantine from restore.
	raw, _ := store.Load(p.cacheKey)
	var d credentialCacheDocument
	json.Unmarshal(raw, &d)
	d.Entries[0].LastUsedAt = time.Now().Add(-2 * time.Hour)
	raw, _ = json.Marshal(d)
	store.Save(p.cacheKey, raw)
	r := newCredentialPool("link", 1, 1, testCredentialFetcher(&calls))
	r.enableCache("pool", store)
	if time.Until(r.entries[0].saturatedUntil) < 10*time.Minute {
		t.Fatal("crashed process quota was immediately reused")
	}
	next := acquireTestLease(t, r, 0)
	next.release()
	if next.creds.Username == l.creds.Username {
		t.Fatal("reused allocation identity from crashed process")
	}
	l.release()
}

func TestCacheBindingExpiryAndCorruption(t *testing.T) {
	for _, kind := range []string{"different-key", "different-link", "expired", "extended-deadline", "corrupt", "duplicate"} {
		t.Run(kind, func(t *testing.T) {
			var calls atomic.Int32
			store := &memoryCredentialCache{}
			p := newCredentialPool("link", 4, 4, testCredentialFetcher(&calls))
			p.enableCache("pool", store)
			l := acquireTestLease(t, p, 0)
			l.markValidated()
			l.release()
			raw, _ := store.Load(p.cacheKey)
			var d credentialCacheDocument
			json.Unmarshal(raw, &d)
			switch kind {
			case "different-key":
				d.Key = "other"
			case "different-link":
				d.Entries[0].Credentials.Link = "other"
			case "expired":
				d.Entries[0].Credentials.ExpiresAt = time.Now().Add(-time.Minute)
			case "extended-deadline":
				d.Entries[0].Credentials.ExpiresAt = time.Now().Add(24 * time.Hour)
			case "duplicate":
				duplicate := d.Entries[0]
				duplicate.Slot = 1
				d.Entries = append(d.Entries, duplicate)
			}
			raw, _ = json.Marshal(d)
			if kind == "corrupt" {
				raw = []byte("not JSON")
			}
			store.Save(p.cacheKey, raw)
			r := newCredentialPool("link", 4, 4, testCredentialFetcher(&calls))
			r.enableCache("pool", store)
			next := acquireTestLease(t, r, 0)
			defer next.release()
			if kind == "extended-deadline" || kind == "duplicate" {
				if calls.Load() != 1 || time.Until(next.creds.ExpiresAt) > 8*time.Hour {
					t.Fatal("restored duplicate or extended credential validity")
				}
				if r.entries[1].creds.Username != "" {
					t.Fatal("same credential loaded into two quota slots")
				}
			} else if calls.Load() != 2 {
				t.Fatal("accepted cache from wrong context or corrupt/expired cache")
			}
		})
	}
}

func TestCacheFailureDoesNotBreakLivePool(t *testing.T) {
	var calls atomic.Int32
	p := newCredentialPool("link", 4, 4, testCredentialFetcher(&calls))
	p.enableCache("pool", &memoryCredentialCache{fail: true})
	l := acquireTestLease(t, p, 0)
	l.markValidated()
	l.release()
	next := acquireTestLease(t, p, 0)
	next.release()
	if calls.Load() != 1 {
		t.Fatal("disk failure prevented in-memory reuse")
	}
}

func TestStaleLeaseCannotInvalidateReplacement(t *testing.T) {
	var calls atomic.Int32
	p := newCredentialPool("link", 1, 1, testCredentialFetcher(&calls))
	old := acquireTestLease(t, p, 0)
	old.release()
	p.entries[0].creds.ExpiresAt = time.Time{}
	next := acquireTestLease(t, p, 0)
	defer next.release()
	next.markValidated()
	for i := 0; i < maxCacheErrors; i++ {
		old.recordAuthError()
	}
	old.markSaturated()
	old.markValidated()
	if !p.isFreshLocked(next.slot, time.Now()) || !p.entries[next.slot].saturatedUntil.IsZero() {
		t.Fatal("late error changed replacement identity")
	}
}

func TestExpiryDoesNotRevokeExistingLease(t *testing.T) {
	var calls atomic.Int32
	p := newCredentialPool("link", 1, 1, testCredentialFetcher(&calls))
	old := acquireTestLease(t, p, 0)
	p.entries[old.slot].creds.ExpiresAt = time.Now().Add(-time.Second)
	next := acquireTestLease(t, p, 0)
	defer next.release()
	defer old.release()
	if old.creds.Password == "" || p.entries[old.slot].active != 1 || next.slot == old.slot {
		t.Fatal("expiry replaced an identity with a live allocation")
	}
}

func TestAuthenticationErrorDetection(t *testing.T) {
	for _, text := range []string{"Allocate error response (error 401: Unauthorized)", "error 403: Forbidden"} {
		if !isAuthError(errors.New(text)) {
			t.Fatalf("missed auth rejection %s", text)
		}
	}
	for _, text := range []string{"dial 1.2.3.4:40123 failed", "error 438: Stale Nonce", "486 Allocation Quota Reached"} {
		if isAuthError(errors.New(text)) {
			t.Fatalf("misclassified %s", text)
		}
	}
}

func TestConcurrentCachedAcquisitionsRespectQuota(t *testing.T) {
	var calls atomic.Int32
	store := &memoryCredentialCache{}
	p := newCredentialPool("link", 10, 10, testCredentialFetcher(&calls))
	p.enableCache("pool", store)
	l := acquireTestLease(t, p, 0)
	l.markValidated()
	l.release()
	r := newCredentialPool("link", 10, 10, testCredentialFetcher(&calls))
	r.enableCache("pool", store)
	var wg sync.WaitGroup
	leases := make(chan *credentialLease, 10)
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			lease, err := r.acquire(context.Background(), id)
			if err != nil {
				t.Error(err)
				return
			}
			leases <- lease
		}(i)
	}
	wg.Wait()
	close(leases)
	if r.entries[0].active != 10 || calls.Load() != 1 {
		t.Fatal("cached quota accounting drifted")
	}
	for lease := range leases {
		lease.release()
	}
}

func TestUnreachableRelayDoesNotPinLongLivedCache(t *testing.T) {
	var calls atomic.Int32
	store := &memoryCredentialCache{}
	p := newCredentialPool("link", 1, 1, testCredentialFetcher(&calls))
	p.enableCache("pool", store)
	l := acquireTestLease(t, p, 0)
	l.markValidated()
	for i := 0; i < 3; i++ {
		l.recordTransportError()
	}
	l.release()
	if p.isFreshLocked(l.slot, time.Now()) {
		t.Fatal("unreachable relay stayed fresh for hours")
	}
	next := acquireTestLease(t, p, 0)
	defer next.release()
	if next.creds.Username == l.creds.Username {
		t.Fatal("unreachable relay identity was reused")
	}
	r := newCredentialPool("link", 1, 1, testCredentialFetcher(&calls))
	r.enableCache("pool", store)
	if r.entries[l.slot].creds.Username != "" {
		t.Fatal("failed identity remained on disk")
	}
}

func TestSuccessfulAllocationResetsTransportFailures(t *testing.T) {
	var calls atomic.Int32
	p := newCredentialPool("link", 1, 1, testCredentialFetcher(&calls))
	l := acquireTestLease(t, p, 0)
	defer l.release()
	l.recordTransportError()
	l.recordTransportError()
	l.markValidated()
	l.recordTransportError()
	if !p.isFreshLocked(l.slot, time.Now()) {
		t.Fatal("successful TCP fallback did not reset setup failures")
	}
}

func readCacheDocument(t *testing.T, p *credentialPool, store *memoryCredentialCache) credentialCacheDocument {
	t.Helper()
	raw, err := store.Load(p.cacheKey)
	if err != nil {
		t.Fatal(err)
	}
	var document credentialCacheDocument
	if err := json.Unmarshal(raw, &document); err != nil {
		t.Fatal(err)
	}
	return document
}

func TestWarmSixteenStreamCyclePersistsFourTransitions(t *testing.T) {
	var calls atomic.Int32
	store := &memoryCredentialCache{}
	seed := newCredentialPool("link", 16, 10, testCredentialFetcher(&calls))
	seed.enableCache("pool", store)
	var seedLeases []*credentialLease
	for id := 0; id < 16; id++ {
		lease := acquireTestLease(t, seed, id)
		lease.markValidated()
		seedLeases = append(seedLeases, lease)
	}
	for _, lease := range seedLeases {
		lease.release()
	}
	p := newCredentialPool("link", 16, 10, testCredentialFetcher(&calls))
	p.enableCache("pool", store)
	store.writes = 0
	var leases []*credentialLease
	for id := 0; id < 16; id++ {
		lease := acquireTestLease(t, p, id)
		lease.markValidated()
		leases = append(leases, lease)
	}
	if store.writes != 2 {
		t.Fatalf("connect saved %d times, want 2", store.writes)
	}
	if p.entries[0].active != 10 || p.entries[1].active != 6 {
		t.Fatal("live quota counts changed")
	}
	for _, entry := range readCacheDocument(t, p, store).Entries {
		if entry.Active <= 0 {
			t.Fatal("active identity was not durably reserved")
		}
	}
	for _, lease := range leases {
		lease.release()
	}
	if store.writes != 4 {
		t.Fatalf("warm cycle saved %d times, want 4", store.writes)
	}
	for _, entry := range readCacheDocument(t, p, store).Entries {
		if entry.Active != 0 {
			t.Fatal("clean disconnect left a crash marker")
		}
	}
	if calls.Load() != 2 {
		t.Fatal("warm cycle unnecessarily fetched credentials")
	}
}

func TestSharedCachedIdentityStaysReservedUntilLastRelease(t *testing.T) {
	var calls atomic.Int32
	store := &memoryCredentialCache{}
	p := newCredentialPool("link", 4, 4, testCredentialFetcher(&calls))
	p.enableCache("pool", store)
	first := acquireTestLease(t, p, 0)
	first.markValidated()
	second := acquireTestLease(t, p, 1)
	first.release()
	if readCacheDocument(t, p, store).Entries[0].Active <= 0 {
		t.Fatal("partial disconnect cleared the durable reservation")
	}
	crashed := newCredentialPool("link", 4, 4, testCredentialFetcher(&calls))
	crashed.enableCache("pool", store)
	if time.Until(crashed.entries[0].saturatedUntil) < 10*time.Minute {
		t.Fatal("crash with one remaining lease did not quarantine the identity")
	}
	second.release()
	clean := newCredentialPool("link", 4, 4, testCredentialFetcher(&calls))
	clean.enableCache("pool", store)
	if !clean.entries[0].saturatedUntil.IsZero() {
		t.Fatal("clean disconnect was restored as a crash")
	}
}

func TestFailedOccupancySaveRetriesOnOtherwiseUnchangedValidation(t *testing.T) {
	var calls atomic.Int32
	store := &memoryCredentialCache{}
	p := newCredentialPool("link", 4, 4, testCredentialFetcher(&calls))
	p.enableCache("pool", store)
	seed := acquireTestLease(t, p, 0)
	seed.markValidated()
	seed.release()
	store.fail = true
	lease := acquireTestLease(t, p, 0)
	if !p.cacheDirty {
		t.Fatal("failed reservation was marked clean")
	}
	store.fail = false
	lease.markValidated()
	if p.cacheDirty || readCacheDocument(t, p, store).Entries[0].Active <= 0 {
		t.Fatal("unchanged validation did not retry failed durable reservation")
	}
	lease.release()
}
