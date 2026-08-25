/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"encoding/binary"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestDurationHistogramReportsResidenceAndResets(t *testing.T) {
	var histogram durationHistogram
	histogram.observeDuration(300 * time.Microsecond)
	histogram.observeDuration(3 * time.Millisecond)
	line := histogram.summaryAndReset("queue-wait")
	if !strings.Contains(line, "queue-wait=") || !strings.Contains(line, "n=2") {
		t.Fatalf("unexpected summary: %q", line)
	}
	if again := histogram.summaryAndReset("queue-wait"); again != "" {
		t.Fatalf("histogram did not reset: %q", again)
	}
}

func TestWGReorderSeparatesDuplicateFromDisplacement(t *testing.T) {
	stats := newWGReorderStats()
	now := time.Unix(1, 0)
	packet := func(counter uint64) []byte {
		buf := make([]byte, 16)
		buf[0] = wgTransportMessageType
		binary.LittleEndian.PutUint32(buf[4:8], 7)
		binary.LittleEndian.PutUint64(buf[8:16], counter)
		return buf
	}
	stats.observe(packet(10), now)
	stats.observe(packet(12), now.Add(time.Millisecond))
	stats.observe(packet(11), now.Add(2*time.Millisecond))
	stats.observe(packet(11), now.Add(3*time.Millisecond))
	line := stats.summaryAndReset("wg-order", now.Add(time.Second))
	if !strings.Contains(line, "reordered=1/") || !strings.Contains(line, "dup=1") {
		t.Fatalf("unexpected reorder summary: %q", line)
	}
}

func TestEWMARejectsSingleRTTSpike(t *testing.T) {
	var value atomic.Int64
	updateEWMA(&value, int64(100*time.Millisecond), 4, 1)
	got := updateEWMA(&value, int64(time.Second), 4, 1)
	if got != int64(280*time.Millisecond) {
		t.Fatalf("EWMA after spike = %s, want 280ms", time.Duration(got))
	}
}
