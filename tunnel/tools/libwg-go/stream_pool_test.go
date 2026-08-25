/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"testing"
	"time"
)

func TestNextRoundRobinUsesOnlyReadyPool(t *testing.T) {
	ready := []int{2, 5, 9}
	want := []int{2, 5, 9, 2, 5, 9}
	var cursor uint64
	for i, expected := range want {
		got, ok := nextRoundRobin(ready, &cursor)
		if !ok || got != expected {
			t.Fatalf("selection %d: got %d (ok=%t), want %d", i, got, ok, expected)
		}
	}
}

func TestNextRoundRobinEmptyPool(t *testing.T) {
	cursor := uint64(7)
	if _, ok := nextRoundRobin([]int(nil), &cursor); ok {
		t.Fatal("empty pool unexpectedly returned an item")
	}
	if cursor != 7 {
		t.Fatalf("empty pool changed cursor to %d", cursor)
	}
}

func TestNextLowestScoreBalancesTies(t *testing.T) {
	items := []int{4, 1, 1, 9}
	var cursor uint64
	first, ok := nextLowestScore(items, &cursor, func(v int) int64 { return int64(v) })
	if !ok || first != 1 {
		t.Fatalf("first selection=%d ok=%t, want 1", first, ok)
	}
	second, ok := nextLowestScore(items, &cursor, func(v int) int64 { return int64(v) })
	if !ok || second != 1 {
		t.Fatalf("second selection=%d ok=%t, want 1", second, ok)
	}
}

func TestReconnectBackoffIsBoundedAndStaggered(t *testing.T) {
	if got := reconnectBackoff(1, 0); got < time.Second || got >= 2*time.Second {
		t.Fatalf("first backoff=%v, want [1s,2s)", got)
	}
	if reconnectBackoff(2, 0) == reconnectBackoff(2, 1) {
		t.Fatal("different streams received identical reconnect delays")
	}
	if got := reconnectBackoff(99, 3); got < 30*time.Second || got >= 31*time.Second {
		t.Fatalf("bounded backoff=%v, want [30s,31s)", got)
	}
}
