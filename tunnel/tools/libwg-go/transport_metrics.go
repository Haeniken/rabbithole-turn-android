/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"fmt"
	"math"
	"sync"
	"sync/atomic"
	"time"
)

type queuedPacket struct {
	buf        []byte
	enqueuedAt int64
}

const (
	durationBucketNs = int64(250 * time.Microsecond)
	durationBuckets  = 2048
)

// durationHistogram measures both the wait for a stream writer and the time
// spent inside socket Write. The timestamp travels with the packet so multiple
// producers cannot detach a sample from its payload.
type durationHistogram struct {
	hist  [durationBuckets + 1]atomic.Uint64
	maxNs atomic.Int64
}

func (h *durationHistogram) observeDuration(d time.Duration) {
	if d < 0 {
		d = 0
	}
	idx := int64(d) / durationBucketNs
	if idx > durationBuckets {
		idx = durationBuckets
	}
	h.hist[idx].Add(1)
	noteAtomicMaxInt64(&h.maxNs, int64(d))
}

func (h *durationHistogram) observeInterval(startedAt, finishedAt int64) {
	if startedAt == 0 || finishedAt == 0 {
		return
	}
	h.observeDuration(time.Duration(finishedAt - startedAt))
}

func (h *durationHistogram) summaryAndReset(name string) string {
	counts := make([]uint64, len(h.hist))
	var total uint64
	for i := range h.hist {
		counts[i] = h.hist[i].Swap(0)
		total += counts[i]
	}
	maxNs := h.maxNs.Swap(0)
	if total == 0 {
		return ""
	}
	return fmt.Sprintf(" %s=%s/%s/%s max=%s n=%d", name,
		durationBucketEdge(histogramPercentile(counts, total, 0.50)),
		durationBucketEdge(histogramPercentile(counts, total, 0.90)),
		durationBucketEdge(histogramPercentile(counts, total, 0.99)),
		time.Duration(maxNs).Round(time.Microsecond), total)
}

func (h *durationHistogram) reset() {
	for i := range h.hist {
		h.hist[i].Store(0)
	}
	h.maxNs.Store(0)
}

func histogramPercentile(counts []uint64, total uint64, fraction float64) int {
	if total == 0 {
		return -1
	}
	want := uint64(math.Ceil(fraction * float64(total)))
	if want == 0 {
		want = 1
	}
	var seen uint64
	for i, count := range counts {
		seen += count
		if seen >= want {
			return i
		}
	}
	return len(counts) - 1
}

func durationBucketEdge(index int) string {
	switch {
	case index < 0:
		return "-"
	case index >= durationBuckets:
		return ">=" + time.Duration(durationBuckets*int(durationBucketNs)).String()
	default:
		return time.Duration(int64(index) * durationBucketNs).String()
	}
}

func noteAtomicMaxInt64(mark *atomic.Int64, candidate int64) {
	for current := mark.Load(); candidate > current; current = mark.Load() {
		if mark.CompareAndSwap(current, candidate) {
			return
		}
	}
}

const (
	wgTransportMessageType = 4
	wgTransportHeaderLen   = 16
	reorderWindow          = 1024
	reorderMaxPeers        = 64
	reorderPeerIdle        = time.Minute
)

type reorderPeer struct {
	max  uint64
	seen [reorderWindow / 64]uint64
	last time.Time
}

func (p *reorderPeer) mark(counter uint64) (duplicate, stale bool) {
	if counter > p.max {
		gap := counter - p.max
		if gap >= reorderWindow {
			for i := range p.seen {
				p.seen[i] = 0
			}
		} else {
			for value := p.max + 1; value <= counter; value++ {
				index := value % reorderWindow
				p.seen[index/64] &^= 1 << (index % 64)
			}
		}
		index := counter % reorderWindow
		p.seen[index/64] |= 1 << (index % 64)
		return false, false
	}
	if p.max-counter >= reorderWindow {
		return false, true
	}
	index := counter % reorderWindow
	if p.seen[index/64]&(1<<(index%64)) != 0 {
		return true, false
	}
	p.seen[index/64] |= 1 << (index % 64)
	return false, false
}

// wgReorderStats is observed at the single shared consumer immediately before
// packets are handed to WireGuard. Measuring in the per-stream producers would
// describe arrival order, not the order the application actually delivers.
type wgReorderStats struct {
	mu        sync.Mutex
	peers     map[uint32]*reorderPeer
	total     uint64
	displaced uint64
	duplicate uint64
	stale     uint64
	rekeys    uint64
	refused   uint64
	maxDepth  uint64
}

func newWGReorderStats() *wgReorderStats {
	return &wgReorderStats{peers: make(map[uint32]*reorderPeer)}
}

func (s *wgReorderStats) observe(packet []byte, now time.Time) {
	receiver, counter, ok := parseWGTransportCounter(packet)
	if !ok {
		return
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	peer := s.peers[receiver]
	if peer == nil {
		if len(s.peers) >= reorderMaxPeers {
			s.refused++
			return
		}
		peer = &reorderPeer{max: counter, last: now}
		s.peers[receiver] = peer
		peer.mark(counter)
		s.rekeys++
		s.total++
		return
	}
	peer.last = now
	s.total++
	duplicate, stale := peer.mark(counter)
	switch {
	case duplicate:
		s.duplicate++
		return
	case stale:
		s.stale++
		return
	case counter > peer.max:
		peer.max = counter
		return
	default:
		depth := peer.max - counter
		s.displaced++
		if depth > s.maxDepth {
			s.maxDepth = depth
		}
	}
}

func (s *wgReorderStats) summaryAndReset(name string, now time.Time) string {
	s.mu.Lock()
	defer s.mu.Unlock()
	for receiver, peer := range s.peers {
		if now.Sub(peer.last) > reorderPeerIdle {
			delete(s.peers, receiver)
		}
	}
	if s.total == 0 && s.refused == 0 {
		return ""
	}
	percentage := 0.0
	if s.total > 0 {
		percentage = 100 * float64(s.displaced) / float64(s.total)
	}
	result := fmt.Sprintf(" %s(total=%d reordered=%d/%.2f%% max-depth=%d dup=%d stale=%d keypairs=%d refused=%d)",
		name, s.total, s.displaced, percentage, s.maxDepth, s.duplicate, s.stale, s.rekeys, s.refused)
	s.total, s.displaced, s.duplicate, s.stale, s.rekeys, s.refused, s.maxDepth = 0, 0, 0, 0, 0, 0, 0
	return result
}

func (s *wgReorderStats) reset() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.peers = make(map[uint32]*reorderPeer)
	s.total, s.displaced, s.duplicate, s.stale, s.rekeys, s.refused, s.maxDepth = 0, 0, 0, 0, 0, 0, 0
}

func updateEWMA(value *atomic.Int64, sample int64, oldWeight, sampleWeight int64) int64 {
	if sample < 0 {
		sample = 0
	}
	for {
		old := value.Load()
		next := sample
		if old > 0 {
			next = (old*oldWeight + sample*sampleWeight) / (oldWeight + sampleWeight)
		}
		if value.CompareAndSwap(old, next) {
			return next
		}
	}
}
