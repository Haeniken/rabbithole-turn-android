/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"context"
	"testing"
)

func TestAdaptivePoolStartsAtMaximumAndNeverShrinksBelowFour(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	streams := make([]*stream, 8)
	for i := range streams {
		streams[i] = &stream{id: i, in: make(chan queuedPacket, 1)}
	}
	started := make(chan int, len(streams))
	pool := newAdaptiveStreamPool(ctx, streams, func(runCtx context.Context, stream *stream) {
		started <- stream.id
		<-runCtx.Done()
	})
	pool.scaleTo(pool.initialTarget())
	if got := pool.activeCount(); got != 8 {
		t.Fatalf("initial active=%d, want 8", got)
	}
	if got := pool.idleTarget(); got != 4 {
		t.Fatalf("idle target=%d, want 4", got)
	}
	pool.scaleTo(2)
	if got := pool.activeCount(); got != 4 {
		t.Fatalf("shrunk active=%d, want 4", got)
	}
}
