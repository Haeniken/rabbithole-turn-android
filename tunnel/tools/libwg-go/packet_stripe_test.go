/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"testing"
	"time"
)

func readyTestStream(id, queueCapacity int) *stream {
	s := &stream{id: id, in: make(chan queuedPacket, queueCapacity)}
	s.ready.Store(true)
	return s
}

func TestPacketStripeSelectorKeepsExactWidth(t *testing.T) {
	serverProbeable.Store(false)
	items := []*stream{readyTestStream(0, 64), readyTestStream(1, 64)}
	selector := newPacketStripeSelector(4)
	var cursor uint64
	now := time.Unix(1, 0)

	want := []int{0, 0, 0, 0, 1, 1, 1, 1}
	for i, id := range want {
		got, ok := selector.next(items, &cursor, now)
		if !ok || got.id != id {
			t.Fatalf("selection %d = (%v, %t), want stream %d", i, got, ok, id)
		}
	}
}

func TestPacketStripeSelectorLeavesUnavailableStreamImmediately(t *testing.T) {
	serverProbeable.Store(false)
	first := readyTestStream(0, 64)
	second := readyTestStream(1, 64)
	items := []*stream{first, second}
	selector := newPacketStripeSelector(16)
	var cursor uint64
	now := time.Unix(1, 0)

	got, ok := selector.next(items, &cursor, now)
	if !ok || got != first {
		t.Fatalf("initial selection = (%v, %t), want first stream", got, ok)
	}
	got, ok = selector.next([]*stream{second}, &cursor, now)
	if !ok || got != second {
		t.Fatalf("selection after removal = (%v, %t), want second stream", got, ok)
	}
}

func TestPacketStripeSelectorEscapesBackedUpStream(t *testing.T) {
	serverProbeable.Store(false)
	first := readyTestStream(0, 2)
	second := readyTestStream(1, 2)
	selector := newPacketStripeSelector(16)
	var cursor uint64
	now := time.Unix(1, 0)

	got, _ := selector.next([]*stream{first, second}, &cursor, now)
	if got != first {
		t.Fatalf("initial selection = %v, want first stream", got)
	}
	first.in <- queuedPacket{}
	first.in <- queuedPacket{}
	got, _ = selector.next([]*stream{first, second}, &cursor, now)
	if got != second {
		t.Fatalf("selection with full first queue = %v, want second stream", got)
	}
}

func TestProbeCapableServerDisablesLegacyReplyWatchdog(t *testing.T) {
	timeout := 30 * time.Second
	if shouldRecycleForMissingReplies(true, 100, time.Hour, timeout) {
		t.Fatal("probe-capable stream must not be recycled from ordinary reply placement")
	}
	if !shouldRecycleForMissingReplies(false, watchdogUnansweredTxLimit, timeout+time.Second, timeout) {
		t.Fatal("legacy server should retain the missing-reply watchdog")
	}
	if shouldRecycleForMissingReplies(false, watchdogUnansweredTxLimit-1, time.Hour, timeout) {
		t.Fatal("legacy watchdog fired before the unanswered-packet threshold")
	}
}
