/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2023 The Pion community <https://pion.ly>
 * Copyright © 2026 WireGuard LLC. All Rights Reserved.
 */

package main

/*
#include <stdlib.h>
#include <android/log.h>
extern int wgProtectSocket(int fd);
extern const char* getNetworkDnsServers(long long network_handle);
*/
import "C"

import (
	"context"
	"crypto/tls"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"

	"github.com/cbeuw/connutil"
	"github.com/google/uuid"
	"github.com/pion/dtls/v3"
	"github.com/pion/dtls/v3/pkg/crypto/selfsign"
	"github.com/pion/logging"
	"github.com/pion/turn/v5"
)

var turnClientTag = C.CString("WireGuard/TurnClient")

func turnLog(format string, args ...interface{}) {
	l := AndroidLogger{level: C.ANDROID_LOG_INFO, tag: turnClientTag}
	l.Printf(format, args...)
}

func protectControl(network, address string, c syscall.RawConn) error {
	return c.Control(func(fd uintptr) {
		C.wgProtectSocket(C.int(fd))
	})
}

func init() {
	os.Setenv("GODEBUG", "netdns=go")
	credentialLog = turnLog
}

func refreshNetworkResources() {
	// Clear DNS cache
	ClearCache()

	turnHTTPClient.CloseIdleConnections()
	turnLog("[NETWORK] HTTP connections and DNS cache cleared")
}

//export wgNotifyNetworkChange
func wgNotifyNetworkChange() {
	marked := markCredentialPoolsForNetworkChange()
	refreshNetworkResources()
	turnLog("[NETWORK] Path change notified: %d active credential slots moved to quota cooldown", marked)
}

var turnHTTPClient = &http.Client{
	Timeout: 20 * time.Second,
	Transport: &http.Transport{
		DialContext: (&net.Dialer{
			Timeout: 30 * time.Second,
			Control: protectControl,
		}).DialContext,
		MaxIdleConns:    100,
		IdleConnTimeout: 90 * time.Second,
	},
}

type stream struct {
	ctx             context.Context
	id              int
	in              chan []byte
	out             net.PacketConn
	peer            atomic.Pointer[net.Addr] // Last seen addr from WireGuard
	ready           atomic.Bool
	sessionID       []byte
	cert            *tls.Certificate
	watchdogTimeout int
	wrapKey         []byte
	getCreds        getCredsFunc
	readySince      atomic.Int64
	lastPongAt      atomic.Int64
	lastPingAt      atomic.Int64
	lastPingSeq     atomic.Uint64
	lastPongSeq     atomic.Uint64
	probeRTT        atomic.Int64
	txBytes         atomic.Uint64
	rxBytes         atomic.Uint64
}

const (
	iPacketBuffMaxSize        = 2048
	watchdogUnansweredTxLimit = 3
	probeInterval             = 30 * time.Second
	probeStaleThreshold       = 120 * time.Second
)

var packetPool = sync.Pool{
	New: func() interface{} {
		return make([]byte, iPacketBuffMaxSize)
	},
}

// Metrics for diagnostics
var (
	dtlsTxDropCount    atomic.Uint64 // Drops in DTLS TX goroutine
	dtlsRxErrorCount   atomic.Uint64 // Errors in DTLS RX goroutine
	relayTxErrorCount  atomic.Uint64 // Errors in relay TX
	relayRxErrorCount  atomic.Uint64 // Errors in relay RX
	noDtlsTxDropCount  atomic.Uint64 // Drops in NoDTLS TX
	noDtlsRxErrorCount atomic.Uint64 // Errors in NoDTLS RX
	queueFullDropCount atomic.Uint64 // Local UDP packets dropped on a full stream queue
	noReadyDropCount   atomic.Uint64 // Local UDP packets received while no stream was healthy
	queuedPacketCount  atomic.Uint64
	queuePeak          atomic.Uint64
	streamReconnects   atomic.Uint64
	turnQuotaErrors    atomic.Uint64
	probeSentCount     atomic.Uint64
	probeRecvCount     atomic.Uint64
	probeZombieCount   atomic.Uint64
	serverProbeable    atomic.Bool
)

func (s *stream) run(link string, peer *net.UDPAddr, udp bool, okchan chan<- struct{}, turnIp string, turnPort int, peerType string) {
	reconnectAttempt := 0
	for {
		select {
		case <-s.ctx.Done():
			return
		default:
		}

		err := func() error {
			s.ready.Store(false)
			sCtx, sCancel := context.WithCancel(s.ctx)
			defer sCancel()

			if s.getCreds == nil {
				return fmt.Errorf("credentials function not initialized")
			}
			lease, err := s.getCreds(sCtx, s.id)
			if err != nil {
				return fmt.Errorf("TURN creds failed: %w", err)
			}
			defer lease.release()
			user := lease.creds.Username
			pass := lease.creds.Password
			addr := lease.creds.ServerAddr

			// Override TURN address if provided
			if turnIp != "" {
				_, origPort, _ := net.SplitHostPort(addr)
				if turnPort != 0 {
					addr = net.JoinHostPort(turnIp, fmt.Sprintf("%d", turnPort))
				} else if origPort != "" {
					addr = net.JoinHostPort(turnIp, origPort)
				} else {
					addr = turnIp
				}
				turnLog("[STREAM %d] Using custom TURN IP: %s", s.id, addr)
			} else if turnPort != 0 {
				origHost, _, _ := net.SplitHostPort(addr)
				addr = net.JoinHostPort(origHost, fmt.Sprintf("%d", turnPort))
				turnLog("[STREAM %d] Using custom TURN port: %s", s.id, addr)
			}

			turnLog("[STREAM %d] Dialing TURN server %s...", s.id, addr)
			// addr is already resolved during credential fetch via cascading DNS, so use DialContext without Resolver
			dialer := &net.Dialer{
				Timeout: 30 * time.Second,
				Control: protectControl,
			}
			var turnConn net.PacketConn
			if udp {
				c, err := dialer.DialContext(sCtx, "udp", addr)
				if err != nil {
					return fmt.Errorf("TURN UDP dial failed: %w", err)
				}
				defer c.Close()
				turnConn = &connectedUDPConn{c.(*net.UDPConn)}
			} else {
				c, err := dialer.DialContext(sCtx, "tcp", addr)
				if err != nil {
					return fmt.Errorf("TURN TCP dial failed: %w", err)
				}
				defer c.Close()
				turnConn = turn.NewSTUNConn(c)
			}

			client, err := turn.NewClient(&turn.ClientConfig{
				STUNServerAddr: addr, TURNServerAddr: addr, Username: user, Password: pass,
				Conn: turnConn, LoggerFactory: logging.NewDefaultLoggerFactory(),
			})
			if err != nil {
				return fmt.Errorf("TURN client creation failed: %w", err)
			}
			defer client.Close()
			if err := client.Listen(); err != nil {
				// Check if this is an authentication error (stale credentials)
				if isAuthError(err) {
					lease.recordAuthError()
				}
				return fmt.Errorf("TURN listen failed: %w", err)
			}

			turnLog("[STREAM %d] Requesting TURN allocation...", s.id)
			relayConn, err := client.Allocate()
			if err != nil {
				// Check if this is an authentication error (stale credentials)
				if isAuthError(err) {
					lease.recordAuthError()
				}
				if isAllocationQuotaError(err) {
					turnQuotaErrors.Add(1)
					cooldown := lease.markSaturated()
					turnLog("[STREAM %d] TURN allocation quota reached; credential slot %d cooling down for %v", s.id, lease.slot, cooldown)
				}
				return fmt.Errorf("TURN allocation failed: %w", err)
			}
			defer relayConn.Close()

			turnLog("[STREAM %d] Allocated relay address: %s", s.id, relayConn.LocalAddr())

			// Delegate to mode-specific handler
			if peerType == "wireguard" {
				return s.runNoDTLS(sCtx, relayConn, peer, okchan)
			}
			// proxy_v2 and proxy_v1 both use DTLS, but v2 sends session+stream handshake
			sendHandshake := peerType != "proxy_v1"
			return s.runDTLS(sCtx, relayConn, peer, okchan, sendHandshake)
		}()
		// Stop routing new packets to this stream as soon as its current
		// allocation exits. Keeping it marked ready during reconnect backoff
		// can otherwise queue packets into a dead transport.
		s.ready.Store(false)
		readySince := s.readySince.Swap(0)

		if s.ctx.Err() == nil {
			if err == nil {
				err = errors.New("TURN stream ended")
			}
			if readySince > 0 && time.Since(time.Unix(0, readySince)) >= 30*time.Second {
				reconnectAttempt = 0
			} else {
				reconnectAttempt++
			}
			streamReconnects.Add(1)
			reconnectDelay := reconnectBackoff(reconnectAttempt, s.id)
			if errors.Is(err, errCaptchaWaitRequired) {
				if remaining := captchaBackoff.remaining(); remaining > reconnectDelay {
					reconnectDelay = remaining
				}
			}
			if requested := retryAfterFromError(err); requested > reconnectDelay {
				reconnectDelay = requested
			}
			turnLog("[STREAM %d] Error: %v. Reconnecting in %v...", s.id, err, reconnectDelay.Round(time.Second))
			select {
			case <-s.ctx.Done():
				return
			case <-time.After(reconnectDelay):
			}
		}
	}
}

// runNoDTLS handles packet relay without DTLS obfuscation
func (s *stream) runNoDTLS(ctx context.Context, relayConn net.PacketConn, peer *net.UDPAddr, okchan chan<- struct{}) error {
	sCtx, sCancel := context.WithCancel(ctx)
	defer sCancel()

	turnLog("[STREAM %d] No DTLS mode - direct relay", s.id)
	turnLog("[STREAM %d] Forwarding to WireGuard server: %s", s.id, peer.String())

	wg := sync.WaitGroup{}
	wg.Add(2)

	// WireGuard backend (s.in channel) -> TURN -> WireGuard server (TX)
	go func() {
		defer wg.Done()
		defer sCancel()
		for {
			select {
			case <-sCtx.Done():
				return
			case b := <-s.in:
				n, err := relayConn.WriteTo(b, peer)
				packetPool.Put(b[:cap(b)])

				if err != nil {
					noDtlsTxDropCount.Add(1)
					turnLog("[STREAM %d] TX error: %v", s.id, err)
					return
				}
				s.txBytes.Add(uint64(n))
			}
		}
	}()

	// WireGuard server -> TURN -> WireGuard backend (s.out socket) (RX)
	go func() {
		defer wg.Done()
		defer sCancel()
		buf := make([]byte, iPacketBuffMaxSize)
		for {
			n, from, err := relayConn.ReadFrom(buf)
			if err != nil {
				noDtlsRxErrorCount.Add(1)
				turnLog("[STREAM %d] RX error: %v", s.id, err)
				return
			}
			if from.String() == peer.String() {
				addr := s.peer.Load()
				if addr == nil {
					turnLog("[STREAM %d] RX: no peer address yet", s.id)
					continue
				}
				if _, err := s.out.WriteTo(buf[:n], *addr); err != nil {
					noDtlsRxErrorCount.Add(1)
					turnLog("[STREAM %d] RX write error: %v", s.id, err)
					return
				}
				s.rxBytes.Add(uint64(n))
			}
		}
	}()

	s.ready.Store(true)
	s.readySince.Store(time.Now().UnixNano())
	select {
	case okchan <- struct{}{}:
	default:
	}

	wg.Wait()
	return nil
}

// runDTLS handles packet relay with DTLS obfuscation
func (s *stream) runDTLS(ctx context.Context, relayConn net.PacketConn, peer *net.UDPAddr, okchan chan<- struct{}, sendHandshake bool) error {
	sCtx, sCancel := context.WithCancel(ctx)
	defer sCancel()

	var dtlsConn *dtls.Conn
	var wrapTX, wrapRX *wrapConn
	if len(s.wrapKey) == wrapKeyLen {
		var wrapErr error
		wrapTX, wrapErr = newWrapConn(s.wrapKey, false)
		if wrapErr != nil {
			return fmt.Errorf("WRAP tx init failed: %w", wrapErr)
		}
		wrapRX, wrapErr = newWrapConn(s.wrapKey, false)
		if wrapErr != nil {
			return fmt.Errorf("WRAP rx init failed: %w", wrapErr)
		}
		turnLog("[STREAM %d] WRAP enabled", s.id)
	}

	c1, c2 := connutil.AsyncPacketPipe()
	defer c1.Close()
	defer c2.Close()

	dtlsConn, err := dtls.Client(c1, peer, &dtls.Config{
		Certificates: []tls.Certificate{*s.cert}, InsecureSkipVerify: true,
		ExtendedMasterSecret:  dtls.RequireExtendedMasterSecret,
		CipherSuites:          []dtls.CipherSuiteID{dtls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256},
		ConnectionIDGenerator: dtls.OnlySendCIDGenerator(),
	})
	if err != nil {
		return fmt.Errorf("DTLS client creation failed: %w", err)
	}
	defer dtlsConn.Close()

	wg := sync.WaitGroup{}
	wg.Add(3)

	// Robust cleanup
	context.AfterFunc(sCtx, func() {
		relayConn.Close()
		c1.Close() // Breaks dtlsConn
	})

	// DTLS <-> Relay (via Pipe) - MUST start before handshake
	go func() {
		defer wg.Done()
		defer sCancel()
		buf := make([]byte, iPacketBuffMaxSize)
		for {
			n, _, err := c2.ReadFrom(buf)
			if err != nil {
				return
			}
			out := buf[:n]
			if wrapTX != nil {
				wrapped := make([]byte, wrapMaxWire(n))
				m, wrapErr := wrapTX.wrapInto(wrapped, out)
				if wrapErr != nil {
					relayTxErrorCount.Add(1)
					turnLog("[STREAM %d] WRAP TX error: %v", s.id, wrapErr)
					return
				}
				out = wrapped[:m]
			}
			if _, err := relayConn.WriteTo(out, peer); err != nil {
				relayTxErrorCount.Add(1)
				turnLog("[STREAM %d] Relay TX error: %v", s.id, err)
				return
			}
		}
	}()

	go func() {
		defer wg.Done()
		defer sCancel()
		readBufSize := iPacketBuffMaxSize
		if wrapRX != nil {
			readBufSize = wrapMaxWire(iPacketBuffMaxSize)
		}
		buf := make([]byte, readBufSize)
		for {
			n, from, err := relayConn.ReadFrom(buf)
			if err != nil {
				if isExpectedStreamShutdown(sCtx, err) {
					return
				}
				relayRxErrorCount.Add(1)
				turnLog("[STREAM %d] Relay RX error: %v", s.id, err)
				return
			}
			if from.String() == peer.String() {
				in := buf[:n]
				if wrapRX != nil {
					plain := make([]byte, iPacketBuffMaxSize)
					m, wrapErr := wrapRX.unwrapPacket(in, plain)
					if wrapErr != nil {
						relayRxErrorCount.Add(1)
						turnLog("[STREAM %d] WRAP RX error: %v", s.id, wrapErr)
						continue
					}
					in = plain[:m]
				}
				if _, err := c2.WriteTo(in, peer); err != nil {
					relayTxErrorCount.Add(1)
					turnLog("[STREAM %d] Relay RX->Pipe error: %v", s.id, err)
					return
				}
			}
		}
	}()

	// Deadline updater
	go func() {
		defer wg.Done()
		ticker := time.NewTicker(5 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-sCtx.Done():
				return
			case <-ticker.C:
				deadline := time.Now().Add(30 * time.Second)
				relayConn.SetDeadline(deadline)
				dtlsConn.SetDeadline(deadline)
				c2.SetDeadline(deadline)
			}
		}
	}()

	// Set explicit deadline for handshake
	turnLog("[STREAM %d] Starting DTLS handshake...", s.id)
	dtlsConn.SetDeadline(time.Now().Add(10 * time.Second))

	if err := dtlsConn.HandshakeContext(sCtx); err != nil {
		turnLog("[STREAM %d] DTLS handshake FAILED: %v", s.id, err)
		return fmt.Errorf("DTLS handshake timeout: %w", err)
	}

	// Clear deadline after successful handshake
	dtlsConn.SetDeadline(time.Time{})
	turnLog("[STREAM %d] DTLS handshake SUCCESS", s.id)

	// Session ID + Stream ID Handshake (17 bytes total) — only for Proxy v2
	var dtlsWriteMu sync.Mutex
	if sendHandshake {
		dtlsConn.SetWriteDeadline(time.Now().Add(5 * time.Second))
		handshakeBuf := make([]byte, 17)
		copy(handshakeBuf[:16], s.sessionID)
		handshakeBuf[16] = byte(s.id)

		if _, err := dtlsConn.Write(handshakeBuf); err != nil {
			return fmt.Errorf("session ID handshake failed: %w", err)
		}
		dtlsConn.SetWriteDeadline(time.Time{})
	}

	s.ready.Store(true)
	now := time.Now()
	s.readySince.Store(now.UnixNano())
	s.lastPongAt.Store(now.UnixNano())
	select {
	case okchan <- struct{}{}:
	default:
	}

	var lastRx atomic.Int64
	lastRx.Store(time.Now().UnixNano())
	var txSinceLastRx atomic.Int32

	wg.Add(3)

	// WireGuard -> DTLS (TX)
	go func() {
		defer wg.Done()
		defer sCancel()
		for {
			select {
			case <-sCtx.Done():
				return
			case b := <-s.in:

				// Do not recycle an idle stream on its first packet. Require several
				// unanswered transmissions so normal low-traffic streams stay warm.
				unansweredTx := txSinceLastRx.Add(1)
				lastRxAt := time.Unix(0, lastRx.Load())
				if s.watchdogTimeout > 0 && unansweredTx >= watchdogUnansweredTxLimit && time.Since(lastRxAt) > time.Duration(s.watchdogTimeout)*time.Second {
					packetPool.Put(b[:cap(b)])
					dtlsTxDropCount.Add(1)
					turnLog("[STREAM %d] Watchdog recycling stream after %d unanswered packets", s.id, unansweredTx)
					return
				}

				dtlsWriteMu.Lock()
				n, err := dtlsConn.Write(b)
				dtlsWriteMu.Unlock()
				packetPool.Put(b[:cap(b)])

				if err != nil {
					dtlsTxDropCount.Add(1)
					turnLog("[STREAM %d] TX error: %v", s.id, err)
					return
				}
				s.txBytes.Add(uint64(n))
			}
		}
	}()

	// DTLS -> WireGuard (RX)
	go func() {
		defer wg.Done()
		defer sCancel()
		buf := make([]byte, iPacketBuffMaxSize)
		for {
			n, err := dtlsConn.Read(buf)
			if err != nil {
				if isExpectedStreamShutdown(sCtx, err) {
					return
				}
				dtlsRxErrorCount.Add(1)
				turnLog("[STREAM %d] RX error: %v", s.id, err)
				return
			}
			lastRx.Store(time.Now().UnixNano())
			txSinceLastRx.Store(0)
			if seq, ok := parseProbePacket(buf[:n]); ok {
				receivedAt := time.Now()
				s.lastPongAt.Store(receivedAt.UnixNano())
				s.lastPongSeq.Store(seq)
				probeRecvCount.Add(1)
				serverProbeable.Store(true)
				if seq == s.lastPingSeq.Load() {
					if sentAt := s.lastPingAt.Load(); sentAt > 0 {
						s.probeRTT.Store(receivedAt.Sub(time.Unix(0, sentAt)).Nanoseconds())
					}
				}
				continue
			}
			if last := s.peer.Load(); last != nil {
				if _, err := s.out.WriteTo(buf[:n], *last); err != nil {
					dtlsRxErrorCount.Add(1)
					turnLog("[STREAM %d] RX write error: %v", s.id, err)
					return
				}
				s.rxBytes.Add(uint64(n))
			}
		}
	}()

	// End-to-end liveness probes use the same 0xff PNG sentinel as the iPhone
	// implementation. A legacy server forwards it to WireGuard, which drops it;
	// no stream is judged by probes until at least one echo proves support.
	go func() {
		defer wg.Done()
		defer sCancel()
		initial := time.NewTimer(2*time.Second + time.Duration(s.id)*150*time.Millisecond)
		defer initial.Stop()
		select {
		case <-sCtx.Done():
			return
		case <-initial.C:
		}

		ticker := time.NewTicker(probeInterval)
		defer ticker.Stop()
		lastTick := time.Now()
		for {
			now := time.Now()
			if gap := now.Sub(lastTick); gap > 90*time.Second {
				// Android may suspend this goroutine while the device sleeps. That
				// is not evidence of a dead allocation, so reset the grace clock.
				s.lastPongAt.Store(now.UnixNano())
			}
			lastTick = now

			seq := s.lastPingSeq.Add(1)
			packet := makeProbePacket(seq)
			s.lastPingAt.Store(now.UnixNano())
			dtlsWriteMu.Lock()
			_, err := dtlsConn.Write(packet)
			dtlsWriteMu.Unlock()
			if err != nil {
				turnLog("[STREAM %d] Probe write error: %v", s.id, err)
				return
			}
			probeSentCount.Add(1)

			if serverProbeable.Load() {
				lastPong := time.Unix(0, s.lastPongAt.Load())
				if stale := time.Since(lastPong); stale > probeStaleThreshold {
					probeZombieCount.Add(1)
					turnLog("[STREAM %d] Probe detected a stale allocation (last echo %v ago, ping=%d, pong=%d)",
						s.id, stale.Round(time.Second), s.lastPingSeq.Load(), s.lastPongSeq.Load())
					return
				}
			}

			select {
			case <-sCtx.Done():
				return
			case <-ticker.C:
			}
		}
	}()

	wg.Wait()
	return nil
}

func isExpectedStreamShutdown(ctx context.Context, err error) bool {
	return ctx.Err() != nil || errors.Is(err, net.ErrClosed) || errors.Is(err, io.ErrClosedPipe)
}

func (s *stream) isHealthy(now time.Time) bool {
	if !s.ready.Load() {
		return false
	}
	if !serverProbeable.Load() {
		return true
	}
	lastPong := s.lastPongAt.Load()
	return lastPong > 0 && now.Sub(time.Unix(0, lastPong)) <= probeStaleThreshold
}

func (s *stream) healthScore() int64 {
	// Queue residence dominates the score. Probe RTT only breaks ties between
	// similarly-loaded streams and is capped so a transient spike cannot starve
	// a still-healthy connection forever.
	queueCost := int64(len(s.in)) * int64(50*time.Millisecond)
	rtt := s.probeRTT.Load()
	if rtt <= 0 {
		rtt = int64(50 * time.Millisecond)
	}
	if rtt > int64(500*time.Millisecond) {
		rtt = int64(500 * time.Millisecond)
	}
	// RTT buckets retain round-robin fairness between paths whose latency is
	// effectively equivalent; a one-millisecond fluctuation must not funnel the
	// entire tunnel through a single stream.
	rttBucket := ((rtt + int64(25*time.Millisecond) - 1) / int64(25*time.Millisecond)) * int64(25*time.Millisecond)
	return queueCost + rttBucket
}

func updateAtomicMax(value *atomic.Uint64, candidate uint64) {
	for current := value.Load(); candidate > current; current = value.Load() {
		if value.CompareAndSwap(current, candidate) {
			return
		}
	}
}

func resetTransportMetrics() {
	for _, counter := range []*atomic.Uint64{
		&dtlsTxDropCount, &dtlsRxErrorCount, &relayTxErrorCount, &relayRxErrorCount,
		&noDtlsTxDropCount, &noDtlsRxErrorCount, &queueFullDropCount, &noReadyDropCount,
		&queuedPacketCount, &queuePeak, &streamReconnects, &turnQuotaErrors,
		&probeSentCount, &probeRecvCount, &probeZombieCount,
	} {
		counter.Store(0)
	}
	serverProbeable.Store(false)
}

func logTransportMetrics(streams []*stream, pool *credentialPool, label string) {
	ready, queueDepth := 0, 0
	var txBytes, rxBytes uint64
	for _, stream := range streams {
		if stream.ready.Load() {
			ready++
		}
		queueDepth += len(stream.in)
		txBytes += stream.txBytes.Load()
		rxBytes += stream.rxBytes.Load()
	}
	fresh, active, saturated, total := pool.snapshot()
	turnLog("[METRICS] %s ready=%d/%d queue=%d peak=%d queued=%d drops(no-ready=%d full=%d tx=%d) bytes(tx=%d rx=%d) reconnects=%d quota486=%d probes=%d/%d zombies=%d probe-capable=%t creds(fresh=%d active=%d saturated=%d total=%d)",
		label, ready, len(streams), queueDepth, queuePeak.Load(), queuedPacketCount.Load(),
		noReadyDropCount.Load(), queueFullDropCount.Load(), dtlsTxDropCount.Load()+noDtlsTxDropCount.Load(),
		txBytes, rxBytes, streamReconnects.Load(), turnQuotaErrors.Load(), probeRecvCount.Load(), probeSentCount.Load(),
		probeZombieCount.Load(), serverProbeable.Load(), fresh, active, saturated, total)
}

var currentTurnCancel context.CancelFunc
var turnMutex sync.Mutex

//export wgTurnProxyStart
func wgTurnProxyStart(peerAddrC *C.char, vklinkC *C.char, modeC *C.char, n C.int, udp C.int, listenAddrC *C.char, turnIpC *C.char, turnPortC C.int, peerTypeC *C.char, streamsPerCredC C.int, watchdogTimeoutC C.int, useWrapC C.int, wrapKeyHexC *C.char, captchaProfileJSONC *C.char, networkHandleC C.longlong) int32 {
	// Refresh process-local networking without treating an ordinary start as a
	// physical path change. Only the explicit JNI notification marks active TURN
	// credential slots as potentially quota-saturated.
	refreshNetworkResources()

	// Initialize system DNS from the current network (fallback to predefined Yandex/Google)
	if networkHandleC != 0 {
		if dnsStr := C.getNetworkDnsServers(C.longlong(networkHandleC)); dnsStr != nil {
			dnsGo := C.GoString(dnsStr)
			C.free(unsafe.Pointer(dnsStr))
			servers := strings.Split(dnsGo, ",")
			InitSystemDns(servers)
		}
	}

	peerAddr := C.GoString(peerAddrC)
	vklink := C.GoString(vklinkC)
	mode := C.GoString(modeC)
	listenAddr := C.GoString(listenAddrC)
	turnIp := C.GoString(turnIpC)
	turnPort := int(turnPortC)
	peerType := C.GoString(peerTypeC)
	requestedStreamsPerCred := int(streamsPerCredC)
	streamsPerCredential := normalizeStreamsPerCred(requestedStreamsPerCred)
	if streamsPerCredential != requestedStreamsPerCred {
		turnLog("[PROXY] StreamsPerCred adjusted from %d to %d to stay within the TURN allocation quota", requestedStreamsPerCred, streamsPerCredential)
	}
	watchdogTimeout := int(watchdogTimeoutC)
	useWrap := int(useWrapC) != 0
	wrapKeyHex := strings.TrimSpace(C.GoString(wrapKeyHexC))
	setCaptchaProfileJSON(C.GoString(captchaProfileJSONC))
	networkHandle := int64(networkHandleC)
	var wrapKey []byte
	if useWrap {
		if udp != 0 || peerType == "wireguard" {
			turnLog("[PROXY] WRAP requires DTLS proxy_v1/proxy_v2 mode")
			return -1
		}
		var err error
		wrapKey, err = hex.DecodeString(wrapKeyHex)
		if err != nil || len(wrapKey) != wrapKeyLen {
			turnLog("[PROXY] Invalid WRAP key: must be 64 hex characters")
			return -1
		}
		turnLog("[PROXY] WRAP mode enabled")
	}

	turnLog("[PROXY] Hub starting on %s (streams=%d, mode=%s, peerType=%s, streamsPerCred=%d, watchdogTimeout=%d, wrap=%t, networkHandle=%d)", listenAddr, int(n), mode, peerType, streamsPerCredential, watchdogTimeout, useWrap, networkHandle)
	turnMutex.Lock()
	if currentTurnCancel != nil {
		currentTurnCancel()
	}
	ctx, cancel := context.WithCancel(context.Background())
	currentTurnCancel = cancel
	turnMutex.Unlock()

	// Resolve peerAddr via cascading DNS (if it's a domain)
	var peer *net.UDPAddr
	host, port, err := net.SplitHostPort(peerAddr)
	if err == nil {
		if ip := net.ParseIP(host); ip == nil {
			// It's a domain name, resolve it
			resolvedIP, err := hostCache.Resolve(context.Background(), host)
			if err != nil {
				turnLog("[DNS] Warning: failed to resolve peer: %v, using original", err)
				peer, err = net.ResolveUDPAddr("udp", peerAddr)
				if err != nil {
					return -1
				}
			} else {
				peerAddr = net.JoinHostPort(resolvedIP, port)
				//turnLog("[DNS] Resolved peer %s -> %s", host, resolvedIP)
				peer, err = net.ResolveUDPAddr("udp", peerAddr)
				if err != nil {
					return -1
				}
			}
		} else {
			peer, err = net.ResolveUDPAddr("udp", peerAddr)
			if err != nil {
				return -1
			}
		}
	} else {
		peer, err = net.ResolveUDPAddr("udp", peerAddr)
		if err != nil {
			return -1
		}
	}

	// Determine link for VK mode (for WB mode, link is just "wb")
	var link string
	if mode == "wb" {
		link = "wb"
	} else {
		parts := strings.Split(vklink, "join/")
		link = parts[len(parts)-1]
		if idx := strings.IndexAny(link, "/?#"); idx != -1 {
			link = link[:idx]
		}
	}
	var fetcher fetchFunc
	if mode == "wb" {
		turnLog("[PROXY] Using WB credential mode")
		fetcher = wbFetch
	} else {
		turnLog("[PROXY] Using VK Link credential mode")
		fetcher = fetchVkCreds
	}
	poolKey := fmt.Sprintf("%s|%s|%d|%d", mode, link, int(n), streamsPerCredential)
	credPool := getCredentialPool(poolKey, link, int(n), streamsPerCredential, fetcher)
	getCreds := func(ctx context.Context, streamID int) (*credentialLease, error) {
		return credPool.acquire(ctx, streamID)
	}

	lc, err := net.ListenPacket("udp", listenAddr)
	if err != nil {
		return -1
	}
	context.AfterFunc(ctx, func() { lc.Close() })

	// Generate fresh Session ID for every run to avoid server-side conflicts
	sessionID, _ := uuid.New().MarshalBinary()
	turnLog("[PROXY] Session ID generated: %x", sessionID)

	// Generate DTLS certificate once for all streams to save CPU
	cert, err := selfsign.GenerateSelfSigned()
	if err != nil {
		turnLog("[PROXY] Failed to generate DTLS certificate: %v", err)
		return -1
	}

	ok := make(chan struct{}, int(n))
	streams := make([]*stream, int(n))
	resetTransportMetrics()
	for i := 0; i < int(n); i++ {
		streams[i] = &stream{ctx: ctx, id: i, in: make(chan []byte, 512), out: lc, sessionID: sessionID, cert: &cert, watchdogTimeout: watchdogTimeout, wrapKey: wrapKey, getCreds: getCreds}
	}
	for i := range streams {
		stream := streams[i]
		go func(delay time.Duration) {
			timer := time.NewTimer(delay)
			defer timer.Stop()
			select {
			case <-ctx.Done():
				return
			case <-timer.C:
				stream.run(link, peer, udp != 0, ok, turnIp, turnPort, peerType)
			}
		}(time.Duration(i) * 200 * time.Millisecond)
	}

	// Readiness changes independently from local WireGuard traffic. Monitor it
	// separately so diagnostics reflect the actual balancing pool even while
	// the tunnel is idle.
	go func() {
		ticker := time.NewTicker(time.Second)
		metricsTicker := time.NewTicker(30 * time.Second)
		defer ticker.Stop()
		defer metricsTicker.Stop()
		lastReady := -1
		for {
			ready := 0
			for _, s := range streams {
				if s.ready.Load() {
					ready++
				}
			}
			if ready != lastReady {
				turnLog("[PROXY] Active balancing pool: %d/%d streams ready", ready, len(streams))
				lastReady = ready
			}
			select {
			case <-ctx.Done():
				logTransportMetrics(streams, credPool, "final")
				return
			case <-ticker.C:
			case <-metricsTicker.C:
				logTransportMetrics(streams, credPool, "periodic")
			}
		}
	}()

	go func() {
		nStreams := int(len(streams))
		readyStreams := make([]*stream, 0, nStreams)
		var nextStream uint64

		for {
			b := packetPool.Get().([]byte)[:iPacketBuffMaxSize]
			nRead, addr, err := lc.ReadFrom(b)
			if err != nil {
				packetPool.Put(b[:cap(b)])
				return
			}

			// Build the current ready pool first, then rotate within that pool.
			// Rotating over all configured slots skews traffic when only a sparse
			// subset is ready (the next ready slot receives several turns).
			readyStreams = readyStreams[:0]
			now := time.Now()
			for _, candidate := range streams {
				if candidate.isHealthy(now) {
					readyStreams = append(readyStreams, candidate)
				}
			}

			if len(readyStreams) == 0 {
				noReadyDropCount.Add(1)
				packetPool.Put(b[:cap(b)])
				continue
			}
			s, _ := nextLowestScore(readyStreams, &nextStream, func(candidate *stream) int64 {
				return candidate.healthScore()
			})

			returnAddr := addr
			s.peer.Store(&returnAddr)

			select {
			case s.in <- b[:nRead]:
				queuedPacketCount.Add(1)
				updateAtomicMax(&queuePeak, uint64(len(s.in)))
			default:
				queueFullDropCount.Add(1)
				packetPool.Put(b[:cap(b)])
			}
		}
	}()

	select {
	case <-ok:
		turnLog("[PROXY] First stream is ready, tunnel can start")
		return 0
	case <-ctx.Done():
		turnLog("[PROXY] PROXY startup cancelled")
		return -1
	}
}

//export wgTurnProxyStop
func wgTurnProxyStop() {
	turnMutex.Lock()
	defer turnMutex.Unlock()
	if currentTurnCancel != nil {
		turnLog("[PROXY] Stopping TURN proxy")
		currentTurnCancel()
		currentTurnCancel = nil
	}
}

type connectedUDPConn struct{ *net.UDPConn }

func (c *connectedUDPConn) WriteTo(p []byte, _ net.Addr) (int, error) { return c.Write(p) }
