/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"encoding/binary"
	"reflect"
	"testing"
	"time"
)

func reorderTestPacket(receiver uint32, counter uint64) []byte {
	packet := make([]byte, wgTransportHeaderLen)
	packet[0] = wgTransportMessageType
	binary.LittleEndian.PutUint32(packet[4:8], receiver)
	binary.LittleEndian.PutUint64(packet[8:16], counter)
	return packet
}

func packetCounter(packet []byte) uint64 {
	return binary.LittleEndian.Uint64(packet[8:16])
}

func TestWGPacketReordererRepairsShortReordering(t *testing.T) {
	r := newWGPacketReorderer()
	now := time.Unix(1, 0)
	var delivered []uint64
	deliver := func(packet []byte) { delivered = append(delivered, packetCounter(packet)) }
	release := func([]byte) { t.Fatal("unexpected packet release") }

	for _, counter := range []uint64{10, 12, 11, 13} {
		r.process(reorderTestPacket(1, counter), now, deliver, release)
	}
	if want := []uint64{10, 11, 12, 13}; !reflect.DeepEqual(delivered, want) {
		t.Fatalf("delivered=%v, want %v", delivered, want)
	}
}

func TestWGPacketReordererFlushesAfterDeadline(t *testing.T) {
	r := newWGPacketReorderer()
	now := time.Unix(1, 0)
	var delivered []uint64
	deliver := func(packet []byte) { delivered = append(delivered, packetCounter(packet)) }
	r.process(reorderTestPacket(1, 10), now, deliver, func([]byte) {})
	r.process(reorderTestPacket(1, 12), now, deliver, func([]byte) {})
	r.process(reorderTestPacket(1, 13), now, deliver, func([]byte) {})
	r.flushExpired(now.Add(wgReorderDelay), deliver)
	if want := []uint64{10, 12, 13}; !reflect.DeepEqual(delivered, want) {
		t.Fatalf("delivered=%v, want %v", delivered, want)
	}
}

func TestWGPacketReordererSeparatesReceiverKeys(t *testing.T) {
	r := newWGPacketReorderer()
	now := time.Unix(1, 0)
	var delivered []uint64
	deliver := func(packet []byte) { delivered = append(delivered, packetCounter(packet)) }
	r.process(reorderTestPacket(1, 1), now, deliver, func([]byte) {})
	r.process(reorderTestPacket(1, 3), now, deliver, func([]byte) {})
	r.process(reorderTestPacket(2, 50), now, deliver, func([]byte) {})
	r.process(reorderTestPacket(1, 2), now, deliver, func([]byte) {})
	if want := []uint64{1, 50, 2, 3}; !reflect.DeepEqual(delivered, want) {
		t.Fatalf("delivered=%v, want %v", delivered, want)
	}
}

func TestWGPacketReordererDropsBufferedDuplicate(t *testing.T) {
	r := newWGPacketReorderer()
	now := time.Unix(1, 0)
	released := 0
	r.process(reorderTestPacket(1, 1), now, func([]byte) {}, func([]byte) { released++ })
	r.process(reorderTestPacket(1, 3), now, func([]byte) {}, func([]byte) { released++ })
	r.process(reorderTestPacket(1, 3), now, func([]byte) {}, func([]byte) { released++ })
	if released != 1 {
		t.Fatalf("released=%d, want 1 duplicate", released)
	}
}
