/* SPDX-License-Identifier: Apache-2.0 */

package main

import "time"

// packetStripeSize is one in the control build. Experimental builds change
// only this value to compare producer-side packet striping against the same
// MTU, stream count, health scoring, and watchdog behavior.
const packetStripeSize = 16

type packetStripeSelector struct {
	width     int
	current   *stream
	remaining int
}

func newPacketStripeSelector(width int) *packetStripeSelector {
	if width < 1 {
		width = 1
	}
	return &packetStripeSelector{width: width}
}

// next assigns consecutive packets to one healthy stream without delaying
// them. Unlike writer-side chunking, this runs at the single producer and is
// therefore guaranteed to engage even when the shared queue is shallow. A
// short per-stream queue bound prevents a stalled path from accumulating a
// whole unbounded stripe.
func (s *packetStripeSelector) next(items []*stream, cursor *uint64, now time.Time) (*stream, bool) {
	if len(items) == 0 {
		s.current = nil
		s.remaining = 0
		return nil, false
	}
	if s.remaining > 0 && containsStream(items, s.current) && streamBelowStripeQueueLimit(s.current, s.width) {
		s.remaining--
		return s.current, true
	}

	selected, ok := nextWithinScoreBand(items, cursor, int64(25*time.Millisecond), func(candidate *stream) int64 {
		return candidate.healthScore(now)
	})
	if !ok {
		s.current = nil
		s.remaining = 0
		return nil, false
	}
	s.current = selected
	s.remaining = s.width - 1
	return selected, true
}

func containsStream(items []*stream, wanted *stream) bool {
	if wanted == nil {
		return false
	}
	for _, item := range items {
		if item == wanted {
			return true
		}
	}
	return false
}

func streamBelowStripeQueueLimit(candidate *stream, width int) bool {
	if candidate == nil || cap(candidate.in) == 0 {
		return false
	}
	limit := width
	if limit > cap(candidate.in) {
		limit = cap(candidate.in)
	}
	return len(candidate.in) < limit
}

func shouldRecycleForMissingReplies(probeCapable bool, unanswered int32, idle, timeout time.Duration) bool {
	// Once the server has echoed an end-to-end probe, that mechanism is the
	// authoritative per-stream liveness signal. Ordinary WireGuard replies can
	// legitimately return over another TURN allocation, so using them as a
	// per-stream watchdog produces false recycling under load.
	return !probeCapable && timeout > 0 && unanswered >= watchdogUnansweredTxLimit && idle > timeout
}
