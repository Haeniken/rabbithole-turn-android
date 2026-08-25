/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"context"
	"sync/atomic"
	"testing"
	"time"
)

func TestProbePacketRoundTrip(t *testing.T) {
	const sequence = uint64(0x0102030405060708)
	packet := makeProbePacket(sequence)
	if len(packet) != 12 {
		t.Fatalf("probe length=%d, want 12", len(packet))
	}
	if got, ok := parseProbePacket(packet); !ok || got != sequence {
		t.Fatalf("parseProbePacket()=(%x,%t), want (%x,true)", got, ok, sequence)
	}
}

func TestProbePacketRejectsWireGuardAndWrongLength(t *testing.T) {
	if _, ok := parseProbePacket([]byte{1, 0, 0, 0}); ok {
		t.Fatal("WireGuard-looking packet was accepted as a probe")
	}
	if _, ok := parseProbePacket(append(makeProbePacket(1), 0)); ok {
		t.Fatal("probe with trailing data was accepted")
	}
}

func TestWaitForProbeEcho(t *testing.T) {
	var pong atomic.Uint64
	go func() {
		time.Sleep(10 * time.Millisecond)
		pong.Store(7)
	}()
	if !waitForProbeEcho(context.Background(), &pong, 7, time.Second) {
		t.Fatal("probe echo was not observed")
	}
}

func TestWaitForProbeEchoTimesOut(t *testing.T) {
	var pong atomic.Uint64
	if waitForProbeEcho(context.Background(), &pong, 1, 20*time.Millisecond) {
		t.Fatal("missing probe echo was accepted")
	}
}
