/* SPDX-License-Identifier: Apache-2.0 */

package main

import "testing"

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
