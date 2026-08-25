/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"
)

const handoverReadyStreams = 2

type transportRuntimeConfig struct {
	maxStreams     int
	link           string
	peer           *net.UDPAddr
	preferUDP      bool
	turnIP         string
	turnPort       int
	peerType       string
	watchdog       int
	wrapKey        []byte
	certificate    *tls.Certificate
	getCreds       getCredsFunc
	credentialPool *credentialPool
}

type streamGeneration struct {
	ctx           context.Context
	cancel        context.CancelFunc
	networkHandle int64
	streams       []*stream
	pool          *adaptiveStreamPool
	ready         chan struct{}
	stopOnce      sync.Once
}

func (g *streamGeneration) readyCount() int {
	ready := 0
	now := time.Now()
	for _, stream := range g.streams {
		if stream.isHealthy(now) {
			ready++
		}
	}
	return ready
}

func (g *streamGeneration) stop() {
	g.stopOnce.Do(func() {
		g.cancel()
		g.pool.stopAll()
	})
}

type turnRuntime struct {
	ctx        context.Context
	config     transportRuntimeConfig
	recv       chan []byte
	lc         net.PacketConn
	returnPeer atomic.Pointer[net.Addr]
	active     atomic.Pointer[streamGeneration]
	handoverMu sync.Mutex
}

func (r *turnRuntime) newGeneration(networkHandle int64) (*streamGeneration, error) {
	if r.config.maxStreams <= 0 {
		return nil, fmt.Errorf("invalid maximum stream count %d", r.config.maxStreams)
	}
	ctx, cancel := context.WithCancel(r.ctx)
	sessionID, err := uuid.New().MarshalBinary()
	if err != nil {
		cancel()
		return nil, fmt.Errorf("generate session ID: %w", err)
	}
	ready := make(chan struct{}, r.config.maxStreams)
	streams := make([]*stream, r.config.maxStreams)
	for i := range streams {
		streams[i] = &stream{
			id:              i,
			in:              make(chan queuedPacket, 512),
			recv:            r.recv,
			sessionID:       sessionID,
			cert:            r.config.certificate,
			watchdogTimeout: r.config.watchdog,
			wrapKey:         r.config.wrapKey,
			getCreds:        r.config.getCreds,
			networkHandle:   networkHandle,
		}
	}
	generation := &streamGeneration{ctx: ctx, cancel: cancel, networkHandle: networkHandle, streams: streams, ready: ready}
	generation.pool = newAdaptiveStreamPool(ctx, streams, func(runCtx context.Context, stream *stream) {
		stream.run(runCtx, r.config.link, r.config.peer, r.config.preferUDP, ready, r.config.turnIP, r.config.turnPort, r.config.peerType)
	})
	context.AfterFunc(r.ctx, generation.stop)
	go generation.pool.run()
	turnLog("[NETWORK] Started candidate stream generation on handle=%d (initial=%d max=%d)",
		networkHandle, generation.pool.initialTarget(), len(streams))
	return generation, nil
}

func (r *turnRuntime) handover(networkHandle int64) error {
	r.handoverMu.Lock()
	defer r.handoverMu.Unlock()
	oldGeneration := r.active.Load()
	if oldGeneration == nil {
		return fmt.Errorf("TURN runtime has no active stream generation")
	}
	if oldGeneration.networkHandle == networkHandle {
		turnLog("[NETWORK] Handover skipped: network handle is unchanged (%d)", networkHandle)
		return nil
	}
	oldGeneration.pool.disableScaling()

	marked := markCredentialPoolsForNetworkChange()
	refreshNetworkResources()
	turnLog("[NETWORK] Preparing make-before-break handover %d -> %d; %d active credential slots cooled down",
		oldGeneration.networkHandle, networkHandle, marked)
	newGeneration, err := r.newGeneration(networkHandle)
	if err != nil {
		oldGeneration.pool.enableScaling()
		return err
	}
	required := handoverReadyStreams
	if required > newGeneration.pool.initialTarget() {
		required = newGeneration.pool.initialTarget()
	}
	deadline := time.NewTimer(30 * time.Second)
	ticker := time.NewTicker(100 * time.Millisecond)
	defer deadline.Stop()
	defer ticker.Stop()
	for newGeneration.readyCount() < required {
		select {
		case <-r.ctx.Done():
			go newGeneration.stop()
			return r.ctx.Err()
		case <-deadline.C:
			ready := newGeneration.readyCount()
			go newGeneration.stop()
			oldGeneration.pool.enableScaling()
			return fmt.Errorf("new network pool readiness timeout: %d/%d streams", ready, required)
		case <-ticker.C:
		}
	}

	r.active.Store(newGeneration)
	newGeneration.pool.enableScaling()
	turnLog("[NETWORK] New path ready with %d stream(s); switching outbound traffic", newGeneration.readyCount())
	select {
	case <-r.ctx.Done():
	case <-time.After(750 * time.Millisecond):
	}
	go func() {
		oldGeneration.stop()
		turnLog("[NETWORK] Make-before-break handover complete: old path drained")
	}()
	return nil
}
