/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"context"
	"sync"
	"sync/atomic"
	"time"
)

const (
	minimumIdleStreams = 4
	scaleInterval      = time.Second
	idleScaleDownAfter = 3 * time.Minute
)

type managedStream struct {
	stream *stream
	mu     sync.Mutex
	active bool
	cancel context.CancelFunc
	done   chan struct{}
}

func (m *managedStream) start(root context.Context, delay time.Duration, launch func(context.Context, *stream)) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.active || root.Err() != nil {
		return false
	}
	drainQueuedPackets(m.stream.in)
	ctx, cancel := context.WithCancel(root)
	done := make(chan struct{})
	m.active, m.cancel, m.done = true, cancel, done
	go func() {
		defer close(done)
		if delay > 0 {
			timer := time.NewTimer(delay)
			defer timer.Stop()
			select {
			case <-ctx.Done():
				m.mu.Lock()
				m.active = false
				m.cancel = nil
				m.mu.Unlock()
				return
			case <-timer.C:
			}
		}
		launch(ctx, m.stream)
		m.stream.ready.Store(false)
		m.mu.Lock()
		m.active = false
		m.cancel = nil
		m.mu.Unlock()
	}()
	return true
}

func (m *managedStream) stop() bool {
	m.mu.Lock()
	if !m.active {
		done := m.done
		m.mu.Unlock()
		if done != nil {
			<-done
		}
		drainQueuedPackets(m.stream.in)
		return false
	}
	cancel, done := m.cancel, m.done
	m.mu.Unlock()
	cancel()
	<-done
	drainQueuedPackets(m.stream.in)
	return true
}

func (m *managedStream) isActive() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.active
}

func drainQueuedPackets(queue chan queuedPacket) {
	for {
		select {
		case item := <-queue:
			packetPool.Put(item.buf[:cap(item.buf)])
		default:
			return
		}
	}
}

type adaptiveStreamPool struct {
	ctx     context.Context
	managed []*managedStream
	launch  func(context.Context, *stream)
	mu      sync.Mutex
	target  int
	scaling atomic.Bool
}

func newAdaptiveStreamPool(ctx context.Context, streams []*stream, launch func(context.Context, *stream)) *adaptiveStreamPool {
	managed := make([]*managedStream, len(streams))
	for i, stream := range streams {
		managed[i] = &managedStream{stream: stream}
	}
	return &adaptiveStreamPool{ctx: ctx, managed: managed, launch: launch}
}

func (p *adaptiveStreamPool) initialTarget() int {
	return len(p.managed)
}

func (p *adaptiveStreamPool) idleTarget() int {
	if len(p.managed) < idleStreamTarget {
		return len(p.managed)
	}
	return idleStreamTarget
}

func (p *adaptiveStreamPool) minimumTarget() int {
	if len(p.managed) < minimumIdleStreams {
		return len(p.managed)
	}
	return minimumIdleStreams
}

func (p *adaptiveStreamPool) scaleTo(target int) {
	if target < p.minimumTarget() {
		target = p.minimumTarget()
	}
	if target > len(p.managed) {
		target = len(p.managed)
	}
	p.mu.Lock()
	oldTarget := p.target
	p.target = target
	p.mu.Unlock()
	if target == oldTarget {
		return
	}
	if target > oldTarget {
		for i := oldTarget; i < target; i++ {
			p.managed[i].start(p.ctx, time.Duration(i-oldTarget)*200*time.Millisecond, p.launch)
		}
	} else {
		for i := oldTarget - 1; i >= target; i-- {
			p.managed[i].stop()
		}
	}
	turnLog("[POOL] Adaptive target changed: %d -> %d (configured max=%d)", oldTarget, target, len(p.managed))
}

func (p *adaptiveStreamPool) targetCount() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.target
}

func (p *adaptiveStreamPool) activeCount() int {
	active := 0
	for _, managed := range p.managed {
		if managed.isActive() {
			active++
		}
	}
	return active
}

func (p *adaptiveStreamPool) stopAll() {
	p.mu.Lock()
	p.target = 0
	p.mu.Unlock()
	for i := len(p.managed) - 1; i >= 0; i-- {
		p.managed[i].stop()
	}
}

func (p *adaptiveStreamPool) enableScaling() {
	p.scaling.Store(true)
}

func (p *adaptiveStreamPool) disableScaling() {
	p.scaling.Store(false)
}

func (p *adaptiveStreamPool) run() {
	if p.ctx.Err() != nil {
		return
	}
	p.scaleTo(p.initialTarget())
	ticker := time.NewTicker(scaleInterval)
	defer ticker.Stop()
	lastSampleAt := time.Now()
	lastPackets, lastBytes := p.trafficTotals()
	var packetsPerSecondEWMA, bitsPerSecondEWMA float64
	var idleSince time.Time
	for {
		select {
		case <-p.ctx.Done():
			return
		case now := <-ticker.C:
			if !p.scaling.Load() {
				lastSampleAt = now
				lastPackets, lastBytes = p.trafficTotals()
				continue
			}
			currentPackets, currentBytes := p.trafficTotals()
			deltaPackets := monotonicDelta(currentPackets, lastPackets)
			deltaBytes := monotonicDelta(currentBytes, lastBytes)
			elapsed := now.Sub(lastSampleAt).Seconds()
			lastSampleAt = now
			lastPackets, lastBytes = currentPackets, currentBytes
			if elapsed <= 0 {
				elapsed = scaleInterval.Seconds()
			}
			packetsPerSecond := float64(deltaPackets) / elapsed
			bitsPerSecond := float64(deltaBytes*8) / elapsed
			packetsPerSecondEWMA = updateRateEWMA(packetsPerSecondEWMA, packetsPerSecond)
			bitsPerSecondEWMA = updateRateEWMA(bitsPerSecondEWMA, bitsPerSecond)
			depth := 0
			var worstWait int64
			for _, managed := range p.managed {
				if !managed.isActive() {
					continue
				}
				depth += len(managed.stream.in)
				if wait := managed.stream.queueWaitEWMA.Load(); wait > worstWait {
					worstWait = wait
				}
			}
			target := p.targetCount()
			if deltaPackets > 0 || deltaBytes > 0 || depth > 0 {
				idleSince = time.Time{}
				desired := desiredStreamTarget(len(p.managed), bitsPerSecondEWMA, packetsPerSecondEWMA, depth, worstWait)
				next := nextGrowthTarget(target, desired, len(p.managed))
				if next > target {
					turnLog("[POOL] Load %.2f Mbit/s %.0f pkt/s (EWMA), queue=%d wait=%s; growing toward %d",
						bitsPerSecondEWMA/1_000_000, packetsPerSecondEWMA, depth, time.Duration(worstWait), desired)
					p.scaleTo(next)
				}
			} else if depth == 0 {
				if idleSince.IsZero() {
					idleSince = now
				} else if now.Sub(idleSince) >= idleScaleDownAfter && target > p.idleTarget() {
					p.scaleTo(p.idleTarget())
				}
			} else {
				idleSince = time.Time{}
			}
		}
	}
}

// trafficTotals covers both upload and download. The previous scaler observed
// only packets queued by local WireGuard, so a download-heavy speed test could
// remain stuck at four streams even while RX was saturated.
func (p *adaptiveStreamPool) trafficTotals() (packets, bytes uint64) {
	for _, managed := range p.managed {
		packets += managed.stream.txPackets.Load() + managed.stream.rxPackets.Load()
		bytes += managed.stream.txBytes.Load() + managed.stream.rxBytes.Load()
	}
	return packets, bytes
}
