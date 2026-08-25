/* SPDX-License-Identifier: Apache-2.0 */

package main

import "time"

const (
	idleStreamTarget          = 4
	trafficEWMAAlpha          = 0.35
	lightTrafficBitsPerSecond = 250_000
	busyTrafficBitsPerSecond  = 3_000_000
	heavyTrafficBitsPerSecond = 7_000_000
	lightTrafficPacketsPerSec = 100
	busyTrafficPacketsPerSec  = 400
	heavyTrafficPacketsPerSec = 800
)

func monotonicDelta(current, previous uint64) uint64 {
	if current < previous {
		return current
	}
	return current - previous
}

func updateRateEWMA(previous, sample float64) float64 {
	if previous <= 0 {
		return sample
	}
	return previous*(1-trafficEWMAAlpha) + sample*trafficEWMAAlpha
}

func desiredStreamTarget(maxStreams int, bitsPerSecond, packetsPerSecond float64, queueDepth int, worstWait int64) int {
	desired := idleStreamTarget
	switch {
	case queueDepth >= 8 || worstWait >= int64(3*time.Millisecond) ||
		bitsPerSecond >= heavyTrafficBitsPerSecond || packetsPerSecond >= heavyTrafficPacketsPerSec:
		desired = maxStreams
	case bitsPerSecond >= busyTrafficBitsPerSecond || packetsPerSecond >= busyTrafficPacketsPerSec:
		desired = 12
	case bitsPerSecond >= lightTrafficBitsPerSecond || packetsPerSecond >= lightTrafficPacketsPerSec:
		desired = 8
	}
	if desired > maxStreams {
		desired = maxStreams
	}
	return desired
}

// Grow one tier per sample. Under a sustained 10 Mbit/s download this produces
// 4 -> 8 -> 12 -> 16 in roughly three seconds without one large credential burst.
func nextGrowthTarget(current, desired, maxStreams int) int {
	if desired <= current {
		return current
	}
	for _, tier := range []int{idleStreamTarget, 8, 12, 16} {
		if tier > maxStreams {
			tier = maxStreams
		}
		if tier > current && tier <= desired {
			return tier
		}
	}
	return desired
}
