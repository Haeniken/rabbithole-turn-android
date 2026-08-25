/* SPDX-License-Identifier: Apache-2.0 */

package main

import "time"

// nextRoundRobin returns one item from the current ready pool and advances the
// cursor. The pool may change size between calls; modulo keeps the cursor valid
// without favoring any configured-but-unavailable stream slots.
func nextRoundRobin[T any](items []T, cursor *uint64) (T, bool) {
	var zero T
	if len(items) == 0 {
		return zero, false
	}
	item := items[*cursor%uint64(len(items))]
	(*cursor)++
	return item, true
}

// nextLowestScore selects the least-loaded healthy item. Round-robin is used
// only between equal-score candidates so no single stream is permanently
// preferred when the pool is balanced.
func nextLowestScore[T any](items []T, cursor *uint64, score func(T) int64) (T, bool) {
	var zero T
	if len(items) == 0 {
		return zero, false
	}
	best := score(items[0])
	ties := 1
	for i := 1; i < len(items); i++ {
		value := score(items[i])
		switch {
		case value < best:
			best = value
			ties = 1
		case value == best:
			ties++
		}
	}
	wanted := int(*cursor % uint64(ties))
	(*cursor)++
	for _, item := range items {
		if score(item) != best {
			continue
		}
		if wanted == 0 {
			return item, true
		}
		wanted--
	}
	return zero, false
}

func reconnectBackoff(attempt, streamID int) time.Duration {
	if attempt < 1 {
		attempt = 1
	}
	shift := attempt - 1
	if shift > 5 {
		shift = 5
	}
	base := time.Second * time.Duration(1<<shift)
	if base > 30*time.Second {
		base = 30 * time.Second
	}
	// Stable per-stream jitter prevents all streams reconnecting in a burst,
	// while keeping unit tests deterministic.
	jitter := time.Duration((streamID*137+attempt*53)%500) * time.Millisecond
	return base + jitter
}
