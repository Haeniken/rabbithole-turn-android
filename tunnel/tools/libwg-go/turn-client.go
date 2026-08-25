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
extern int wgProtectSocketForNetwork(int fd, long long network_handle);
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

func protectControlForNetwork(networkHandle int64) func(string, string, syscall.RawConn) error {
	return func(network, address string, c syscall.RawConn) error {
		return c.Control(func(fd uintptr) {
			C.wgProtectSocketForNetwork(C.int(fd), C.longlong(networkHandle))
		})
	}
}

// logTCPSocketDiagnostics records the kernel values that matter when a TURN
// allocation is carried over TCP. It deliberately does not tune them: this is
// read-only instrumentation for separating application queueing from kernel
// socket pressure and path-MTU effects on real Android networks.
func logTCPSocketDiagnostics(streamID int, conn net.Conn) {
	if !detailedTransportDiagnostics.Load() {
		return
	}
	tcpConn, ok := conn.(*net.TCPConn)
	if !ok {
		return
	}
	rawConn, err := tcpConn.SyscallConn()
	if err != nil {
		turnLog("[STREAM %d] TCP diagnostics unavailable: %v", streamID, err)
		return
	}
	sendBuffer, receiveBuffer, maxSegment, noDelay := -1, -1, -1, -1
	var socketErr error
	if err := rawConn.Control(func(fd uintptr) {
		get := func(level, option int, target *int) {
			if socketErr != nil {
				return
			}
			*target, socketErr = syscall.GetsockoptInt(int(fd), level, option)
		}
		get(syscall.SOL_SOCKET, syscall.SO_SNDBUF, &sendBuffer)
		get(syscall.SOL_SOCKET, syscall.SO_RCVBUF, &receiveBuffer)
		get(syscall.IPPROTO_TCP, syscall.TCP_MAXSEG, &maxSegment)
		get(syscall.IPPROTO_TCP, syscall.TCP_NODELAY, &noDelay)
	}); err != nil {
		turnLog("[STREAM %d] TCP diagnostics unavailable: %v", streamID, err)
		return
	}
	if socketErr != nil {
		turnLog("[STREAM %d] TCP diagnostics unavailable: %v", streamID, socketErr)
		return
	}
	turnLog("[STREAM %d] TCP diagnostics: sndbuf=%d rcvbuf=%d mss=%d nodelay=%d", streamID, sendBuffer, receiveBuffer, maxSegment, noDelay)
}

func logUDPSocketDiagnostics(streamID int, conn *net.UDPConn) {
	if !detailedTransportDiagnostics.Load() {
		return
	}
	rawConn, err := conn.SyscallConn()
	if err != nil {
		turnLog("[STREAM %d] UDP diagnostics unavailable: %v", streamID, err)
		return
	}
	sendBuffer, receiveBuffer := -1, -1
	var socketErr error
	if err := rawConn.Control(func(fd uintptr) {
		sendBuffer, socketErr = syscall.GetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_SNDBUF)
		if socketErr == nil {
			receiveBuffer, socketErr = syscall.GetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_RCVBUF)
		}
	}); err != nil {
		turnLog("[STREAM %d] UDP diagnostics unavailable: %v", streamID, err)
		return
	}
	if socketErr != nil {
		turnLog("[STREAM %d] UDP diagnostics unavailable: %v", streamID, socketErr)
		return
	}
	turnLog("[STREAM %d] UDP diagnostics: sndbuf=%d rcvbuf=%d local=%s remote=%s setup-timeout=%v",
		streamID, sendBuffer, receiveBuffer, conn.LocalAddr(), conn.RemoteAddr(), turnUDPSetupTimeout)
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
	id              int
	in              chan queuedPacket
	recv            chan<- []byte
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
	probeLoss       atomic.Int64 // parts per million
	queueWaitEWMA   atomic.Int64
	writeWaitEWMA   atomic.Int64
	errorPenalty    atomic.Int64
	penaltyAt       atomic.Int64
	networkHandle   int64
	txPackets       atomic.Uint64
	rxPackets       atomic.Uint64
	txBytes         atomic.Uint64
	rxBytes         atomic.Uint64
}

const (
	iPacketBuffMaxSize        = 2048
	watchdogUnansweredTxLimit = 3
	probeInterval             = 30 * time.Second
	probeStaleThreshold       = 120 * time.Second
	serverReorderCapability   = 0x80
	turnUDPSetupTimeout       = 5 * time.Second
	turnTransportTCPOnly      = 0
	turnTransportUDPPreferred = 1
)

var packetPool = sync.Pool{
	New: func() interface{} {
		return make([]byte, iPacketBuffMaxSize)
	},
}

// Metrics for diagnostics
var (
	dtlsTxDropCount              atomic.Uint64 // Drops in DTLS TX goroutine
	dtlsRxErrorCount             atomic.Uint64 // Errors in DTLS RX goroutine
	relayTxErrorCount            atomic.Uint64 // Errors in relay TX
	relayRxErrorCount            atomic.Uint64 // Errors in relay RX
	noDtlsTxDropCount            atomic.Uint64 // Drops in NoDTLS TX
	noDtlsRxErrorCount           atomic.Uint64 // Errors in NoDTLS RX
	queueFullDropCount           atomic.Uint64 // Local UDP packets dropped on a full stream queue
	noReadyDropCount             atomic.Uint64 // Local UDP packets received while no stream was healthy
	queuedPacketCount            atomic.Uint64
	queuePeak                    atomic.Uint64
	streamReconnects             atomic.Uint64
	turnQuotaErrors              atomic.Uint64
	turnUDPAttemptCount          atomic.Uint64
	turnUDPAllocationCount       atomic.Uint64
	turnUDPFailureCount          atomic.Uint64
	turnTCPFallbackCount         atomic.Uint64
	probeSentCount               atomic.Uint64
	probeRecvCount               atomic.Uint64
	probeZombieCount             atomic.Uint64
	serverProbeable              atomic.Bool
	rxQueueFullDrop              atomic.Uint64
	queueResidence               durationHistogram
	socketWriteDelay             durationHistogram
	downlinkArrivalReorder       = newWGReorderStats()
	downlinkReorder              = newWGReorderStats()
	detailedTransportDiagnostics atomic.Bool
)

func shouldFallbackFromUDP(ctx context.Context, err error) bool {
	return err != nil && ctx.Err() == nil && !isAuthError(err) && !isAllocationQuotaError(err)
}

func (s *stream) runTurnTransport(
	ctx context.Context,
	addr, user, pass string,
	lease *credentialLease,
	peer *net.UDPAddr,
	okchan chan<- struct{},
	peerType string,
	useUDP bool,
) error {
	transportName := "TCP"
	if useUDP {
		transportName = "UDP"
		turnUDPAttemptCount.Add(1)
	}
	turnLog("[STREAM %d] Dialing TURN/%s server %s...", s.id, transportName, addr)
	dialer := &net.Dialer{
		Timeout: 30 * time.Second,
		Control: protectControlForNetwork(s.networkHandle),
	}

	var rawConn net.Conn
	var turnConn net.PacketConn
	var udpSetupTimedOut atomic.Bool
	var udpSetupFinished atomic.Bool
	var udpSetupTimer *time.Timer
	if useUDP {
		conn, err := dialer.DialContext(ctx, "udp", addr)
		if err != nil {
			return fmt.Errorf("TURN UDP dial failed: %w", err)
		}
		rawConn = conn
		udpConn := conn.(*net.UDPConn)
		logUDPSocketDiagnostics(s.id, udpConn)
		turnConn = &connectedUDPConn{udpConn}
		udpSetupTimer = time.AfterFunc(turnUDPSetupTimeout, func() {
			if udpSetupFinished.CompareAndSwap(false, true) {
				udpSetupTimedOut.Store(true)
				_ = rawConn.Close()
			}
		})
	} else {
		conn, err := dialer.DialContext(ctx, "tcp", addr)
		if err != nil {
			return fmt.Errorf("TURN TCP dial failed: %w", err)
		}
		rawConn = conn
		logTCPSocketDiagnostics(s.id, conn)
		turnConn = newTurnTCPPacketConn(conn)
	}
	defer rawConn.Close()
	if udpSetupTimer != nil {
		defer udpSetupTimer.Stop()
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
		if isAuthError(err) {
			lease.recordAuthError()
		}
		return fmt.Errorf("TURN listen failed: %w", err)
	}

	turnLog("[STREAM %d] Requesting TURN/%s allocation...", s.id, transportName)
	relayConn, err := client.Allocate()
	if udpSetupTimer != nil {
		if udpSetupFinished.CompareAndSwap(false, true) {
			udpSetupTimer.Stop()
		}
	}
	if udpSetupTimedOut.Load() {
		if err == nil {
			relayConn.Close()
		}
		return fmt.Errorf("TURN/UDP setup timed out after %v", turnUDPSetupTimeout)
	}
	if err != nil {
		if isAuthError(err) {
			lease.recordAuthError()
		}
		if isAllocationQuotaError(err) {
			turnQuotaErrors.Add(1)
			s.noteErrorPenalty(500 * time.Millisecond)
			cooldown := lease.markSaturated()
			turnLog("[STREAM %d] TURN allocation quota reached; credential slot %d cooling down for %v", s.id, lease.slot, cooldown)
		}
		return fmt.Errorf("TURN allocation failed: %w", err)
	}
	defer relayConn.Close()
	if useUDP {
		turnUDPAllocationCount.Add(1)
	}

	turnLog("[STREAM %d] Allocated relay address over TURN/%s: %s", s.id, transportName, relayConn.LocalAddr())
	if peerType == "wireguard" {
		return s.runNoDTLS(ctx, relayConn, peer, okchan)
	}
	return s.runDTLS(ctx, relayConn, peer, okchan, peerType != "proxy_v1")
}

func (s *stream) run(ctx context.Context, link string, peer *net.UDPAddr, preferUDP bool, okchan chan<- struct{}, turnIp string, turnPort int, peerType string) {
	reconnectAttempt := 0
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		err := func() error {
			s.ready.Store(false)
			sCtx, sCancel := context.WithCancel(ctx)
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

			if !preferUDP {
				return s.runTurnTransport(sCtx, addr, user, pass, lease, peer, okchan, peerType, false)
			}

			udpErr := s.runTurnTransport(sCtx, addr, user, pass, lease, peer, okchan, peerType, true)
			if udpErr != nil {
				turnUDPFailureCount.Add(1)
			}
			if !shouldFallbackFromUDP(sCtx, udpErr) {
				return udpErr
			}

			// Do not let the scheduler enqueue packets into an allocation that has
			// already failed while its TCP replacement is being established.
			s.ready.Store(false)
			s.readySince.Store(0)
			turnTCPFallbackCount.Add(1)
			turnLog("[STREAM %d] TURN/UDP unavailable (%v); falling back to TURN/TCP", s.id, udpErr)
			return s.runTurnTransport(sCtx, addr, user, pass, lease, peer, okchan, peerType, false)
		}()
		// Stop routing new packets to this stream as soon as its current
		// allocation exits. Keeping it marked ready during reconnect backoff
		// can otherwise queue packets into a dead transport.
		s.ready.Store(false)
		readySince := s.readySince.Swap(0)

		if ctx.Err() == nil {
			if err == nil {
				err = errors.New("TURN stream ended")
			}
			if readySince > 0 && time.Since(time.Unix(0, readySince)) >= 30*time.Second {
				reconnectAttempt = 0
			} else {
				reconnectAttempt++
			}
			streamReconnects.Add(1)
			s.noteErrorPenalty(100 * time.Millisecond)
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
			case <-ctx.Done():
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
			case item := <-s.in:
				startedAt := time.Now().UnixNano()
				if detailedTransportDiagnostics.Load() {
					queueResidence.observeInterval(item.enqueuedAt, startedAt)
				}
				updateEWMA(&s.queueWaitEWMA, startedAt-item.enqueuedAt, 7, 1)
				n, err := relayConn.WriteTo(item.buf, peer)
				finishedAt := time.Now().UnixNano()
				if detailedTransportDiagnostics.Load() {
					socketWriteDelay.observeInterval(startedAt, finishedAt)
				}
				updateEWMA(&s.writeWaitEWMA, finishedAt-startedAt, 7, 1)
				packetPool.Put(item.buf[:cap(item.buf)])

				if err != nil {
					noDtlsTxDropCount.Add(1)
					s.noteErrorPenalty(250 * time.Millisecond)
					turnLog("[STREAM %d] TX error: %v", s.id, err)
					return
				}
				s.txPackets.Add(1)
				s.txBytes.Add(uint64(n))
			}
		}
	}()

	peerString := peer.String()

	// WireGuard server -> TURN -> WireGuard backend (s.out socket) (RX)
	go func() {
		defer wg.Done()
		defer sCancel()
		for {
			buf := packetPool.Get().([]byte)[:iPacketBuffMaxSize]
			n, from, err := relayConn.ReadFrom(buf)
			if err != nil {
				packetPool.Put(buf[:cap(buf)])
				noDtlsRxErrorCount.Add(1)
				turnLog("[STREAM %d] RX error: %v", s.id, err)
				return
			}
			if sameRelayPeer(from, peer, peerString) {
				if s.enqueueReceived(sCtx, buf[:n]) {
					s.rxPackets.Add(1)
					s.rxBytes.Add(uint64(n))
				}
			} else {
				packetPool.Put(buf[:cap(buf)])
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

	dtlsRelay, err := newDTLSRelayPacketConn(relayConn, peer, s.wrapKey, s)
	if err != nil {
		return err
	}
	if len(s.wrapKey) == wrapKeyLen {
		turnLog("[STREAM %d] WRAP enabled", s.id)
	}

	dtlsConn, err := dtls.Client(dtlsRelay, peer, &dtls.Config{
		Certificates: []tls.Certificate{*s.cert}, InsecureSkipVerify: true,
		ExtendedMasterSecret:  dtls.RequireExtendedMasterSecret,
		CipherSuites:          []dtls.CipherSuiteID{dtls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256},
		ConnectionIDGenerator: dtls.OnlySendCIDGenerator(),
	})
	if err != nil {
		return fmt.Errorf("DTLS client creation failed: %w", err)
	}
	defer dtlsConn.Close()

	context.AfterFunc(sCtx, func() { _ = dtlsRelay.Close() })

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

	// Session ID + Stream ID Handshake (17 bytes total) — only for Proxy v2.
	// Bit 7 tells an updated server that this client can repair bounded
	// downlink reordering. Legacy servers ignore this non-WireGuard datagram,
	// and legacy clients keep sending the original unflagged stream ID.
	var dtlsWriteMu sync.Mutex
	if sendHandshake {
		dtlsConn.SetWriteDeadline(time.Now().Add(5 * time.Second))
		handshakeBuf := make([]byte, 17)
		copy(handshakeBuf[:16], s.sessionID)
		handshakeBuf[16] = byte(s.id) | serverReorderCapability

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

	wg := sync.WaitGroup{}
	wg.Add(4)

	// Keep inactive allocations bounded without an intermediate packet pipe.
	go func() {
		defer wg.Done()
		ticker := time.NewTicker(5 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-sCtx.Done():
				return
			case <-ticker.C:
				_ = dtlsConn.SetDeadline(time.Now().Add(30 * time.Second))
			}
		}
	}()

	// WireGuard -> DTLS (TX)
	go func() {
		defer wg.Done()
		defer sCancel()
		for {
			select {
			case <-sCtx.Done():
				return
			case item := <-s.in:
				b := item.buf
				startedAt := time.Now().UnixNano()
				if detailedTransportDiagnostics.Load() {
					queueResidence.observeInterval(item.enqueuedAt, startedAt)
				}
				updateEWMA(&s.queueWaitEWMA, startedAt-item.enqueuedAt, 7, 1)

				// Do not recycle an idle stream on its first packet. Require several
				// unanswered transmissions so normal low-traffic streams stay warm.
				unansweredTx := txSinceLastRx.Add(1)
				lastRxAt := time.Unix(0, lastRx.Load())
				if shouldRecycleForMissingReplies(
					serverProbeable.Load(),
					unansweredTx,
					time.Since(lastRxAt),
					time.Duration(s.watchdogTimeout)*time.Second,
				) {
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
					s.noteErrorPenalty(250 * time.Millisecond)
					turnLog("[STREAM %d] TX error: %v", s.id, err)
					return
				}
				s.txPackets.Add(1)
				s.txBytes.Add(uint64(n))
			}
		}
	}()

	// DTLS -> WireGuard (RX)
	go func() {
		defer wg.Done()
		defer sCancel()
		for {
			buf := packetPool.Get().([]byte)[:iPacketBuffMaxSize]
			n, err := dtlsConn.Read(buf)
			if err != nil {
				packetPool.Put(buf[:cap(buf)])
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
				packetPool.Put(buf[:cap(buf)])
				receivedAt := time.Now()
				s.lastPongAt.Store(receivedAt.UnixNano())
				updateAtomicMax(&s.lastPongSeq, seq)
				probeRecvCount.Add(1)
				serverProbeable.Store(true)
				if seq == s.lastPingSeq.Load() {
					if sentAt := s.lastPingAt.Load(); sentAt > 0 {
						rtt := receivedAt.Sub(time.Unix(0, sentAt)).Nanoseconds()
						updateEWMA(&s.probeRTT, rtt, 4, 1)
						updateEWMA(&s.probeLoss, 0, 9, 1)
					}
				}
				continue
			}
			if s.enqueueReceived(sCtx, buf[:n]) {
				s.rxPackets.Add(1)
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
			if serverProbeable.Load() && seq > 1 && s.lastPongSeq.Load() < seq-1 {
				updateEWMA(&s.probeLoss, 1_000_000, 9, 1)
			}
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

func (s *stream) enqueueReceived(ctx context.Context, packet []byte) bool {
	select {
	case s.recv <- packet:
		return true
	case <-ctx.Done():
		packetPool.Put(packet[:cap(packet)])
		return false
	default:
		rxQueueFullDrop.Add(1)
		packetPool.Put(packet[:cap(packet)])
		return false
	}
}

func (s *stream) noteErrorPenalty(penalty time.Duration) {
	const maximum = int64(2 * time.Second)
	for {
		current := s.errorPenalty.Load()
		next := current + int64(penalty)
		if next > maximum {
			next = maximum
		}
		if s.errorPenalty.CompareAndSwap(current, next) {
			s.penaltyAt.Store(time.Now().UnixNano())
			return
		}
	}
}

func (s *stream) decayedErrorPenalty(now time.Time) int64 {
	penalty := s.errorPenalty.Load()
	updatedAt := s.penaltyAt.Load()
	if penalty <= 0 || updatedAt <= 0 {
		return 0
	}
	// Every ten seconds halves the effect of a past error. The original value is
	// retained for diagnostics; selection observes only the decayed value.
	steps := int(now.Sub(time.Unix(0, updatedAt)) / (10 * time.Second))
	if steps <= 0 {
		return penalty
	}
	if steps >= 8 {
		return 0
	}
	return penalty >> steps
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

func (s *stream) healthScore(now time.Time) int64 {
	// Depth captures immediate pressure; the two EWMAs distinguish a harmless
	// burst from a queue/socket that is actually delaying packets.
	queueCost := int64(len(s.in))*int64(10*time.Millisecond) + s.queueWaitEWMA.Load() + s.writeWaitEWMA.Load()
	rtt := s.probeRTT.Load()
	if rtt <= 0 {
		rtt = int64(50 * time.Millisecond)
	}
	if rtt > int64(500*time.Millisecond) {
		rtt = int64(500 * time.Millisecond)
	}
	// RTT buckets and the scheduler's score band provide hysteresis: a transient
	// one-millisecond movement cannot move all traffic to another allocation.
	rttBucket := ((rtt + int64(25*time.Millisecond) - 1) / int64(25*time.Millisecond)) * int64(25*time.Millisecond)
	lossCost := s.probeLoss.Load() * int64(500*time.Millisecond) / 1_000_000
	return queueCost + rttBucket + lossCost + s.decayedErrorPenalty(now)
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
		&turnUDPAttemptCount, &turnUDPAllocationCount, &turnUDPFailureCount, &turnTCPFallbackCount,
		&probeSentCount, &probeRecvCount, &probeZombieCount, &rxQueueFullDrop,
	} {
		counter.Store(0)
	}
	serverProbeable.Store(false)
	queueResidence.reset()
	socketWriteDelay.reset()
	downlinkArrivalReorder.reset()
	downlinkReorder.reset()
	resetReorderBufferMetrics()
}

func logTransportMetrics(streams []*stream, pool *credentialPool, label string) {
	if !detailedTransportDiagnostics.Load() {
		return
	}
	ready, queueDepth := 0, 0
	var txPackets, rxPackets, txBytes, rxBytes uint64
	for _, stream := range streams {
		if stream.ready.Load() {
			ready++
		}
		queueDepth += len(stream.in)
		txPackets += stream.txPackets.Load()
		rxPackets += stream.rxPackets.Load()
		txBytes += stream.txBytes.Load()
		rxBytes += stream.rxBytes.Load()
	}
	fresh, active, saturated, total := pool.snapshot()
	waitSummary := queueResidence.summaryAndReset("queue-wait")
	writeSummary := socketWriteDelay.summaryAndReset("socket-write")
	now := time.Now()
	arrivalSummary := downlinkArrivalReorder.summaryAndReset("wg-arrival", now)
	reorderSummary := downlinkReorder.summaryAndReset("wg-order", now)
	turnLog("[METRICS] %s ready=%d/%d queue=%d peak=%d queued=%d drops(no-ready=%d full=%d rx-full=%d tx=%d) packets(tx=%d rx=%d) bytes(tx=%d rx=%d) reconnects=%d quota486=%d turn-udp(attempts=%d allocations=%d failures=%d tcp-fallbacks=%d) probes=%d/%d zombies=%d probe-capable=%t creds(fresh=%d active=%d saturated=%d total=%d)%s%s%s%s%s",
		label, ready, len(streams), queueDepth, queuePeak.Load(), queuedPacketCount.Load(),
		noReadyDropCount.Load(), queueFullDropCount.Load(), rxQueueFullDrop.Load(), dtlsTxDropCount.Load()+noDtlsTxDropCount.Load(),
		txPackets, rxPackets, txBytes, rxBytes, streamReconnects.Load(), turnQuotaErrors.Load(),
		turnUDPAttemptCount.Load(), turnUDPAllocationCount.Load(), turnUDPFailureCount.Load(), turnTCPFallbackCount.Load(),
		probeRecvCount.Load(), probeSentCount.Load(),
		probeZombieCount.Load(), serverProbeable.Load(), fresh, active, saturated, total,
		waitSummary, writeSummary, arrivalSummary, reorderSummary, reorderBufferMetrics())
}

var currentTurnCancel context.CancelFunc
var currentTurnRuntime *turnRuntime
var turnMutex sync.Mutex

//export wgTurnProxyStart
func wgTurnProxyStart(peerAddrC *C.char, vklinkC *C.char, modeC *C.char, n C.int, udp C.int, listenAddrC *C.char, turnIpC *C.char, turnPortC C.int, peerTypeC *C.char, streamsPerCredC C.int, watchdogTimeoutC C.int, useWrapC C.int, wrapKeyHexC *C.char, captchaProfileJSONC *C.char, detailedDiagnosticsC C.int, networkHandleC C.longlong) int32 {
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
	transportMode := int(udp)
	if transportMode < turnTransportTCPOnly || transportMode > turnTransportUDPPreferred {
		turnLog("[PROXY] Invalid TURN transport mode: %d", transportMode)
		return -1
	}
	preferUDP := transportMode == turnTransportUDPPreferred
	useWrap := int(useWrapC) != 0
	wrapKeyHex := strings.TrimSpace(C.GoString(wrapKeyHexC))
	setCaptchaProfileJSON(C.GoString(captchaProfileJSONC))
	detailedTransportDiagnostics.Store(int(detailedDiagnosticsC) != 0)
	networkHandle := int64(networkHandleC)
	var wrapKey []byte
	if useWrap {
		// UDP-first can use WRAP because WRAP is applied at the DTLS relay
		// boundary; raw WireGuard mode still has no DTLS layer to wrap.
		if peerType == "wireguard" {
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

	transportDescription := "TURN/TCP"
	if preferUDP {
		transportDescription = "TURN/UDP with TURN/TCP fallback"
	}
	turnLog("[PROXY] Hub starting on %s (streams=%d, mode=%s, peerType=%s, transport=%s, streamsPerCred=%d, watchdogTimeout=%d, wrap=%t, networkHandle=%d)", listenAddr, int(n), mode, peerType, transportDescription, streamsPerCredential, watchdogTimeout, useWrap, networkHandle)
	turnMutex.Lock()
	if currentTurnCancel != nil {
		currentTurnCancel()
	}
	ctx, cancel := context.WithCancel(context.Background())
	currentTurnCancel = cancel
	currentTurnRuntime = nil
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

	if mode != "vk_link" {
		turnLog("[PROXY] Unsupported credential mode: %s", mode)
		return -1
	}
	parts := strings.Split(vklink, "join/")
	link := parts[len(parts)-1]
	if idx := strings.IndexAny(link, "/?#"); idx != -1 {
		link = link[:idx]
	}
	turnLog("[PROXY] Using VK Link credentials")
	fetcher := fetchVkCreds
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

	// Generate DTLS certificate once for all streams to save CPU
	cert, err := selfsign.GenerateSelfSigned()
	if err != nil {
		turnLog("[PROXY] Failed to generate DTLS certificate: %v", err)
		return -1
	}

	resetTransportMetrics()
	runtime := &turnRuntime{
		ctx:  ctx,
		recv: make(chan []byte, 1024),
		lc:   lc,
		config: transportRuntimeConfig{
			maxStreams:     int(n),
			link:           link,
			peer:           peer,
			preferUDP:      preferUDP,
			turnIP:         turnIp,
			turnPort:       turnPort,
			peerType:       peerType,
			watchdog:       watchdogTimeout,
			wrapKey:        wrapKey,
			certificate:    &cert,
			getCreds:       getCreds,
			credentialPool: credPool,
		},
	}
	generation, err := runtime.newGeneration(networkHandle)
	if err != nil {
		cancel()
		turnLog("[PROXY] Failed to create stream generation: %v", err)
		return -1
	}
	runtime.active.Store(generation)
	generation.pool.enableScaling()
	turnMutex.Lock()
	currentTurnRuntime = runtime
	turnMutex.Unlock()

	// Readiness changes independently from local WireGuard traffic. Monitor it
	// separately so diagnostics reflect the actual balancing pool even while
	// the tunnel is idle.
	go func() {
		ticker := time.NewTicker(time.Second)
		metricsTicker := time.NewTicker(30 * time.Second)
		defer ticker.Stop()
		defer metricsTicker.Stop()
		lastReady, lastActive := -1, -1
		for {
			generation := runtime.active.Load()
			if generation == nil {
				return
			}
			ready := 0
			for _, s := range generation.streams {
				if s.ready.Load() {
					ready++
				}
			}
			active := generation.pool.activeCount()
			if ready != lastReady || active != lastActive {
				turnLog("[PROXY] Active balancing pool: %d/%d streams ready (configured max=%d, network=%d)", ready, active, len(generation.streams), generation.networkHandle)
				lastReady = ready
				lastActive = active
			}
			select {
			case <-ctx.Done():
				logTransportMetrics(generation.streams, credPool, "final")
				return
			case <-ticker.C:
			case <-metricsTicker.C:
				logTransportMetrics(generation.streams, credPool, "periodic")
			}
		}
	}()

	// All TURN receive paths merge here. Measuring and writing at this single
	// consumer records the exact order delivered to WireGuard.
	go func() {
		reorderer := newWGPacketReorderer()
		release := func(packet []byte) { packetPool.Put(packet[:cap(packet)]) }
		deliver := func(packet []byte) {
			if detailedTransportDiagnostics.Load() {
				downlinkReorder.observe(packet, time.Now())
			}
			addr := runtime.returnPeer.Load()
			if addr != nil {
				if _, writeErr := lc.WriteTo(packet, *addr); writeErr != nil && ctx.Err() == nil {
					dtlsRxErrorCount.Add(1)
					turnLog("[PROXY] WireGuard RX write error: %v", writeErr)
				}
			}
			release(packet)
		}
		timer := time.NewTimer(time.Hour)
		if !timer.Stop() {
			<-timer.C
		}
		defer timer.Stop()
		defer reorderer.close(release)
		var scheduled time.Time
		updateTimer := func() <-chan time.Time {
			deadline, ok := reorderer.nextDeadline()
			if !ok {
				if !scheduled.IsZero() && !timer.Stop() {
					select {
					case <-timer.C:
					default:
					}
				}
				scheduled = time.Time{}
				return nil
			}
			if scheduled.Equal(deadline) {
				return timer.C
			}
			if !scheduled.IsZero() && !timer.Stop() {
				select {
				case <-timer.C:
				default:
				}
			}
			delay := time.Until(deadline)
			if delay < 0 {
				delay = 0
			}
			timer.Reset(delay)
			scheduled = deadline
			return timer.C
		}
		for {
			timerC := updateTimer()
			select {
			case <-ctx.Done():
				return
			case packet := <-runtime.recv:
				now := time.Now()
				if detailedTransportDiagnostics.Load() {
					downlinkArrivalReorder.observe(packet, now)
				}
				reorderer.process(packet, now, deliver, release)
			case <-timerC:
				scheduled = time.Time{}
				reorderer.flushExpired(time.Now(), deliver)
			}
		}
	}()

	go func() {
		readyStreams := make([]*stream, 0, int(n))
		var nextStream uint64
		stripeSelector := newPacketStripeSelector(packetStripeSize)
		turnLog("[PROXY] Packet striping width: %d", packetStripeSize)

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
			generation := runtime.active.Load()
			if generation == nil {
				noReadyDropCount.Add(1)
				packetPool.Put(b[:cap(b)])
				continue
			}
			for _, candidate := range generation.streams {
				if candidate.isHealthy(now) {
					readyStreams = append(readyStreams, candidate)
				}
			}

			if len(readyStreams) == 0 {
				noReadyDropCount.Add(1)
				packetPool.Put(b[:cap(b)])
				continue
			}
			s, _ := stripeSelector.next(readyStreams, &nextStream, now)

			returnAddr := addr
			runtime.returnPeer.Store(&returnAddr)

			select {
			case s.in <- queuedPacket{buf: b[:nRead], enqueuedAt: time.Now().UnixNano()}:
				queuedPacketCount.Add(1)
				updateAtomicMax(&queuePeak, uint64(len(s.in)))
			default:
				queueFullDropCount.Add(1)
				packetPool.Put(b[:cap(b)])
			}
		}
	}()

	select {
	case <-generation.ready:
		turnLog("[PROXY] First stream is ready, tunnel can start")
		return 0
	case <-ctx.Done():
		turnLog("[PROXY] PROXY startup cancelled")
		return -1
	}
}

//export wgTurnProxyHandover
func wgTurnProxyHandover(networkHandleC C.longlong) int32 {
	networkHandle := int64(networkHandleC)
	if networkHandle == 0 {
		turnLog("[NETWORK] Refusing handover without a physical network handle")
		return -1
	}
	if dnsStr := C.getNetworkDnsServers(networkHandleC); dnsStr != nil {
		dnsGo := C.GoString(dnsStr)
		C.free(unsafe.Pointer(dnsStr))
		InitSystemDns(strings.Split(dnsGo, ","))
	}
	turnMutex.Lock()
	runtime := currentTurnRuntime
	turnMutex.Unlock()
	if runtime == nil {
		turnLog("[NETWORK] Handover requested without an active TURN runtime")
		return -1
	}
	if err := runtime.handover(networkHandle); err != nil {
		turnLog("[NETWORK] Make-before-break handover failed: %v", err)
		return -1
	}
	return 0
}

//export wgTurnProxyStop
func wgTurnProxyStop() {
	turnMutex.Lock()
	defer turnMutex.Unlock()
	if currentTurnCancel != nil {
		turnLog("[PROXY] Stopping TURN proxy")
		currentTurnCancel()
		currentTurnCancel = nil
		currentTurnRuntime = nil
	}
}

type connectedUDPConn struct{ *net.UDPConn }

func (c *connectedUDPConn) WriteTo(p []byte, _ net.Addr) (int, error) { return c.Write(p) }
