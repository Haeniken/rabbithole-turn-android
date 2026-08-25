/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"encoding/binary"
	"fmt"
	"sync/atomic"
	"time"
)

const (
	wgReorderDelay       = 2 * time.Millisecond
	wgReorderBufferSize  = 32
	wgReorderCounterSpan = 128
)

var (
	reorderHeldCount      atomic.Uint64
	reorderRecoveredCount atomic.Uint64
	reorderTimeoutCount   atomic.Uint64
	reorderOverflowCount  atomic.Uint64
	reorderDuplicateCount atomic.Uint64
	reorderLateCount      atomic.Uint64
	reorderPendingCount   atomic.Int64
)

type pendingWGPacket struct {
	counter uint64
	packet  []byte
}

type wgPacketReorderPeer struct {
	initialized bool
	expected    uint64
	pending     [wgReorderBufferSize]pendingWGPacket
	count       int
	deadline    time.Time
	lastSeen    time.Time
}

type wgPacketReorderer struct {
	peers map[uint32]*wgPacketReorderPeer
}

func newWGPacketReorderer() *wgPacketReorderer {
	return &wgPacketReorderer{peers: make(map[uint32]*wgPacketReorderPeer)}
}

func parseWGTransportCounter(packet []byte) (receiver uint32, counter uint64, ok bool) {
	if len(packet) < wgTransportHeaderLen || packet[0] != wgTransportMessageType {
		return 0, 0, false
	}
	return binary.LittleEndian.Uint32(packet[4:8]), binary.LittleEndian.Uint64(packet[8:16]), true
}

func (r *wgPacketReorderer) process(packet []byte, now time.Time, deliver, release func([]byte)) {
	receiver, counter, ok := parseWGTransportCounter(packet)
	if !ok {
		deliver(packet)
		return
	}
	peer := r.peers[receiver]
	if peer == nil {
		peer = &wgPacketReorderPeer{}
		r.peers[receiver] = peer
	}
	peer.lastSeen = now
	if !peer.initialized {
		peer.initialized = true
		peer.expected = counter + 1
		deliver(packet)
		return
	}

	switch {
	case counter < peer.expected:
		reorderLateCount.Add(1)
		deliver(packet)
	case counter == peer.expected:
		deliver(packet)
		peer.expected++
		recovered := peer.flushContiguous(deliver)
		if recovered > 0 {
			reorderRecoveredCount.Add(uint64(recovered))
		}
	case counter-peer.expected > wgReorderCounterSpan:
		reorderOverflowCount.Add(1)
		peer.flushAll(deliver)
		deliver(packet)
		peer.expected = counter + 1
	case peer.count == len(peer.pending):
		reorderOverflowCount.Add(1)
		peer.flushIncluding(counter, packet, deliver)
	default:
		if !peer.insert(counter, packet) {
			reorderDuplicateCount.Add(1)
			release(packet)
			return
		}
		reorderHeldCount.Add(1)
		reorderPendingCount.Add(1)
		if peer.deadline.IsZero() {
			peer.deadline = now.Add(wgReorderDelay)
		}
	}
}

func (p *wgPacketReorderPeer) insert(counter uint64, packet []byte) bool {
	index := p.count
	for i := 0; i < p.count; i++ {
		switch {
		case p.pending[i].counter == counter:
			return false
		case p.pending[i].counter > counter:
			index = i
			i = p.count
		}
	}
	copy(p.pending[index+1:p.count+1], p.pending[index:p.count])
	p.pending[index] = pendingWGPacket{counter: counter, packet: packet}
	p.count++
	return true
}

func (p *wgPacketReorderPeer) popFirst() pendingWGPacket {
	packet := p.pending[0]
	copy(p.pending[0:p.count-1], p.pending[1:p.count])
	p.count--
	p.pending[p.count] = pendingWGPacket{}
	reorderPendingCount.Add(-1)
	if p.count == 0 {
		p.deadline = time.Time{}
	}
	return packet
}

func (p *wgPacketReorderPeer) flushContiguous(deliver func([]byte)) int {
	flushed := 0
	for p.count > 0 && p.pending[0].counter == p.expected {
		packet := p.popFirst()
		deliver(packet.packet)
		p.expected++
		flushed++
	}
	return flushed
}

func (p *wgPacketReorderPeer) flushAll(deliver func([]byte)) int {
	flushed := 0
	for p.count > 0 {
		packet := p.popFirst()
		deliver(packet.packet)
		if packet.counter >= p.expected {
			p.expected = packet.counter + 1
		}
		flushed++
	}
	return flushed
}

func (p *wgPacketReorderPeer) flushIncluding(counter uint64, packet []byte, deliver func([]byte)) {
	deliveredCurrent := false
	for p.count > 0 {
		if !deliveredCurrent && counter < p.pending[0].counter {
			deliver(packet)
			deliveredCurrent = true
			if counter >= p.expected {
				p.expected = counter + 1
			}
		}
		pending := p.popFirst()
		deliver(pending.packet)
		if pending.counter >= p.expected {
			p.expected = pending.counter + 1
		}
	}
	if !deliveredCurrent {
		deliver(packet)
		if counter >= p.expected {
			p.expected = counter + 1
		}
	}
}

func (r *wgPacketReorderer) flushExpired(now time.Time, deliver func([]byte)) {
	for receiver, peer := range r.peers {
		if peer.count > 0 && !peer.deadline.After(now) {
			flushed := peer.flushAll(deliver)
			reorderTimeoutCount.Add(uint64(flushed))
		}
		if peer.count == 0 && now.Sub(peer.lastSeen) > reorderPeerIdle {
			delete(r.peers, receiver)
		}
	}
}

func (r *wgPacketReorderer) nextDeadline() (time.Time, bool) {
	var earliest time.Time
	for _, peer := range r.peers {
		if peer.count == 0 || peer.deadline.IsZero() {
			continue
		}
		if earliest.IsZero() || peer.deadline.Before(earliest) {
			earliest = peer.deadline
		}
	}
	return earliest, !earliest.IsZero()
}

func (r *wgPacketReorderer) close(release func([]byte)) {
	for _, peer := range r.peers {
		for peer.count > 0 {
			packet := peer.popFirst()
			release(packet.packet)
		}
	}
	r.peers = make(map[uint32]*wgPacketReorderPeer)
}

func resetReorderBufferMetrics() {
	for _, counter := range []*atomic.Uint64{
		&reorderHeldCount, &reorderRecoveredCount, &reorderTimeoutCount,
		&reorderOverflowCount, &reorderDuplicateCount, &reorderLateCount,
	} {
		counter.Store(0)
	}
	reorderPendingCount.Store(0)
}

func reorderBufferMetrics() string {
	return fmt.Sprintf(" reorder-buffer(held=%d recovered=%d timeout=%d overflow=%d dup=%d late=%d pending=%d)",
		reorderHeldCount.Load(), reorderRecoveredCount.Load(), reorderTimeoutCount.Load(),
		reorderOverflowCount.Load(), reorderDuplicateCount.Load(), reorderLateCount.Load(), reorderPendingCount.Load())
}
