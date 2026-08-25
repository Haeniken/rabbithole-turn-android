/* SPDX-License-Identifier: Apache-2.0 */

package main

import "encoding/binary"

// This wire value is intentionally identical to the mature iPhone transport.
// 0xff cannot be a WireGuard message type (valid values are 1..4), so an old
// proxy can safely forward the packet without understanding it.
var probePingMagic = [4]byte{0xff, 'P', 'N', 'G'}

const probePacketLen = len(probePingMagic) + 8

func makeProbePacket(seq uint64) []byte {
	packet := make([]byte, probePacketLen)
	copy(packet, probePingMagic[:])
	binary.BigEndian.PutUint64(packet[len(probePingMagic):], seq)
	return packet
}

func parseProbePacket(packet []byte) (uint64, bool) {
	if len(packet) != probePacketLen {
		return 0, false
	}
	for i, expected := range probePingMagic {
		if packet[i] != expected {
			return 0, false
		}
	}
	return binary.BigEndian.Uint64(packet[len(probePingMagic):]), true
}
