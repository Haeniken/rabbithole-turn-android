/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 WireGuard LLC. All Rights Reserved.
 */

package main

import (
	"context"
	"fmt"
	"sync/atomic"
	"testing"
)

func TestNormalizeStreamsPerCred(t *testing.T) {
	tests := []struct {
		requested int
		want      int
	}{
		{requested: -1, want: 1},
		{requested: 0, want: 1},
		{requested: 1, want: 1},
		{requested: 4, want: 4},
		{requested: 10, want: 10},
		{requested: 16, want: 10},
	}

	for _, test := range tests {
		if got := normalizeStreamsPerCred(test.requested); got != test.want {
			t.Fatalf("requested=%d: got %d, want %d", test.requested, got, test.want)
		}
	}
}

func TestCredentialPoolIncludesReserve(t *testing.T) {
	base, total := credentialPoolSize(16, 10)
	if base != 2 || total != 3 {
		t.Fatalf("credentialPoolSize(16,10)=(%d,%d), want (2,3)", base, total)
	}
}

func TestCredentialPoolSharesUpToQuota(t *testing.T) {
	var fetches atomic.Int32
	pool := newCredentialPool("link", 16, 10, func(context.Context, string) (string, string, string, error) {
		n := fetches.Add(1)
		return fmt.Sprintf("user-%d", n), "pass", "127.0.0.1:3478", nil
	})
	leases := make([]*credentialLease, 0, 11)
	for streamID := 0; streamID < 10; streamID++ {
		lease, err := pool.acquire(context.Background(), streamID)
		if err != nil {
			t.Fatalf("stream %d acquire: %v", streamID, err)
		}
		leases = append(leases, lease)
	}
	if got := fetches.Load(); got != 1 {
		t.Fatalf("first ten streams fetched %d credential sets, want 1", got)
	}
	lease, err := pool.acquire(context.Background(), 10)
	if err != nil {
		t.Fatalf("stream 10 acquire: %v", err)
	}
	leases = append(leases, lease)
	if got := fetches.Load(); got != 2 {
		t.Fatalf("eleventh stream fetched %d credential sets, want 2", got)
	}
	for _, lease := range leases {
		lease.release()
	}
}

func TestCredentialPoolUsesReserveWhileSaturatedSlotIsActive(t *testing.T) {
	var fetches atomic.Int32
	pool := newCredentialPool("link", 4, 4, func(context.Context, string) (string, string, string, error) {
		n := fetches.Add(1)
		return fmt.Sprintf("user-%d", n), "pass", "127.0.0.1:3478", nil
	})
	first, err := pool.acquire(context.Background(), 0)
	if err != nil {
		t.Fatal(err)
	}
	if cooldown := first.markSaturated(); cooldown != vkActiveAllocationsCooldown {
		t.Fatalf("active saturation cooldown=%v, want %v", cooldown, vkActiveAllocationsCooldown)
	}
	second, err := pool.acquire(context.Background(), 1)
	if err != nil {
		t.Fatalf("reserve acquisition failed: %v", err)
	}
	if first.slot == second.slot {
		t.Fatalf("saturated slot %d was reused instead of reserve", first.slot)
	}
	if got := fetches.Load(); got != 2 {
		t.Fatalf("reserve path fetched %d sets, want 2", got)
	}
	first.release()
	second.release()
}

func TestAllocationQuotaDetection(t *testing.T) {
	for _, text := range []string{"486 Allocation Quota Reached", "allocation quota exceeded", "quota reached"} {
		if !isAllocationQuotaError(fmt.Errorf("%s", text)) {
			t.Fatalf("did not detect %q", text)
		}
	}
	if isAllocationQuotaError(fmt.Errorf("401 Unauthorized")) {
		t.Fatal("authentication error misclassified as quota")
	}
}
