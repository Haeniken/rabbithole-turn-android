/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"fmt"
	"net"
	"sync"
	"time"
)

// dtlsRelayPacketConn lets Pion DTLS operate directly on the TURN allocation.
// The previous AsyncPacketPipe path copied every record through an unbounded
// bytes.Buffer and two forwarding goroutines per stream. This adapter keeps
// the exact wire format while applying WRAP at the PacketConn boundary.
type dtlsRelayPacketConn struct {
	relay      net.PacketConn
	peer       *net.UDPAddr
	peerString string
	wrapTX     *wrapConn
	wrapRX     *wrapConn
	owner      *stream

	txMu   sync.Mutex
	rxMu   sync.Mutex
	txWire []byte
	rxWire []byte
}

func newDTLSRelayPacketConn(relay net.PacketConn, peer *net.UDPAddr, wrapKey []byte, owner *stream) (*dtlsRelayPacketConn, error) {
	c := &dtlsRelayPacketConn{relay: relay, peer: peer, peerString: peer.String(), owner: owner}
	if len(wrapKey) == 0 {
		return c, nil
	}
	if len(wrapKey) != wrapKeyLen {
		return nil, fmt.Errorf("WRAP key length %d, want %d", len(wrapKey), wrapKeyLen)
	}
	var err error
	c.wrapTX, err = newWrapConn(wrapKey, false)
	if err != nil {
		return nil, fmt.Errorf("WRAP tx init failed: %w", err)
	}
	c.wrapRX, err = newWrapConn(wrapKey, false)
	if err != nil {
		return nil, fmt.Errorf("WRAP rx init failed: %w", err)
	}
	c.txWire = make([]byte, wrapMaxWire(iPacketBuffMaxSize))
	c.rxWire = make([]byte, wrapMaxWire(iPacketBuffMaxSize))
	return c, nil
}

func sameRelayPeer(addr net.Addr, peer *net.UDPAddr, peerString string) bool {
	if udpAddr, ok := addr.(*net.UDPAddr); ok {
		return udpAddr.Port == peer.Port && udpAddr.Zone == peer.Zone && udpAddr.IP.Equal(peer.IP)
	}
	return addr != nil && addr.String() == peerString
}

func (c *dtlsRelayPacketConn) ReadFrom(dst []byte) (int, net.Addr, error) {
	c.rxMu.Lock()
	defer c.rxMu.Unlock()
	for {
		if c.wrapRX == nil {
			n, addr, err := c.relay.ReadFrom(dst)
			if err != nil {
				relayRxErrorCount.Add(1)
				return 0, addr, err
			}
			if sameRelayPeer(addr, c.peer, c.peerString) {
				return n, addr, nil
			}
			continue
		}

		need := wrapMaxWire(len(dst))
		if cap(c.rxWire) < need {
			c.rxWire = make([]byte, need)
		}
		n, addr, err := c.relay.ReadFrom(c.rxWire[:cap(c.rxWire)])
		if err != nil {
			relayRxErrorCount.Add(1)
			return 0, addr, err
		}
		if !sameRelayPeer(addr, c.peer, c.peerString) {
			continue
		}
		plainLen, err := c.wrapRX.unwrapPacket(c.rxWire[:n], dst)
		if err != nil {
			// TURN allocations may receive unrelated datagrams. A malformed WRAP
			// packet must not tear down an otherwise healthy stream; this keeps the
			// direct adapter's behavior identical to the former relay goroutine.
			relayRxErrorCount.Add(1)
			if c.owner != nil {
				turnLog("[STREAM %d] WRAP RX error: %v", c.owner.id, err)
			}
			continue
		}
		return plainLen, addr, nil
	}
}

func (c *dtlsRelayPacketConn) WriteTo(payload []byte, _ net.Addr) (int, error) {
	c.txMu.Lock()
	defer c.txMu.Unlock()
	startedAt := time.Now().UnixNano()
	if c.wrapTX == nil {
		_, err := c.relay.WriteTo(payload, c.peer)
		finishedAt := time.Now().UnixNano()
		if detailedTransportDiagnostics.Load() {
			socketWriteDelay.observeInterval(startedAt, finishedAt)
		}
		if c.owner != nil {
			updateEWMA(&c.owner.writeWaitEWMA, finishedAt-startedAt, 7, 1)
		}
		if err != nil {
			relayTxErrorCount.Add(1)
			return 0, err
		}
		return len(payload), nil
	}

	need := wrapMaxWire(len(payload))
	if cap(c.txWire) < need {
		c.txWire = make([]byte, need)
	}
	n, err := c.wrapTX.wrapInto(c.txWire[:need], payload)
	if err == nil {
		_, err = c.relay.WriteTo(c.txWire[:n], c.peer)
	}
	finishedAt := time.Now().UnixNano()
	if detailedTransportDiagnostics.Load() {
		socketWriteDelay.observeInterval(startedAt, finishedAt)
	}
	if c.owner != nil {
		updateEWMA(&c.owner.writeWaitEWMA, finishedAt-startedAt, 7, 1)
	}
	if err != nil {
		relayTxErrorCount.Add(1)
		return 0, err
	}
	return len(payload), nil
}

func (c *dtlsRelayPacketConn) Close() error        { return c.relay.Close() }
func (c *dtlsRelayPacketConn) LocalAddr() net.Addr { return c.relay.LocalAddr() }
func (c *dtlsRelayPacketConn) SetDeadline(deadline time.Time) error {
	return c.relay.SetDeadline(deadline)
}
func (c *dtlsRelayPacketConn) SetReadDeadline(deadline time.Time) error {
	return c.relay.SetReadDeadline(deadline)
}
func (c *dtlsRelayPacketConn) SetWriteDeadline(deadline time.Time) error {
	return c.relay.SetWriteDeadline(deadline)
}

var _ net.PacketConn = (*dtlsRelayPacketConn)(nil)
