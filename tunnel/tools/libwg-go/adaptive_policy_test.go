/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"testing"
	"time"
)

func TestDesiredStreamTargetUsesBidirectionalTraffic(t *testing.T) {
	tests := []struct {
		name    string
		bits    float64
		packets float64
		depth   int
		wait    time.Duration
		want    int
	}{
		{name: "idle", want: 4},
		{name: "light bytes", bits: 500_000, want: 8},
		{name: "busy packets", packets: 500, want: 12},
		{name: "heavy download", bits: 10_000_000, want: 16},
		{name: "queue pressure", depth: 8, want: 16},
		{name: "real queue delay", wait: 4 * time.Millisecond, want: 16},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := desiredStreamTarget(16, test.bits, test.packets, test.depth, int64(test.wait)); got != test.want {
				t.Fatalf("target=%d, want %d", got, test.want)
			}
		})
	}
}

func TestNextGrowthTargetAdvancesOneTier(t *testing.T) {
	for _, test := range []struct {
		current int
		desired int
		want    int
	}{
		{4, 16, 8},
		{8, 16, 12},
		{12, 16, 16},
		{4, 12, 8},
		{8, 8, 8},
	} {
		if got := nextGrowthTarget(test.current, test.desired, 16); got != test.want {
			t.Fatalf("nextGrowthTarget(%d, %d)=%d, want %d", test.current, test.desired, got, test.want)
		}
	}
}
