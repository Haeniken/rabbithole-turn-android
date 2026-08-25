/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"bufio"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"sync"
	"time"
)

const (
	turnTCPReaderSize = 128 * 1024
	stunHeaderSize    = 20
	stunMagicCookie   = 0x2112a442
)

var (
	errInvalidTURNFrame  = errors.New("invalid TURN-over-TCP frame")
	errTURNFrameTooLarge = errors.New("TURN-over-TCP frame does not fit destination buffer")
)

// turnTCPPacketConn packetizes TURN-over-TCP without the per-read append/copy
// performed by Pion's generic STUNConn. One persistent bufio.Reader retains
// coalesced TCP frames, while Peek+Discard avoids another intermediate slice.
type turnTCPPacketConn struct {
	net.Conn
	reader *bufio.Reader
	readMu sync.Mutex
}

func newTurnTCPPacketConn(conn net.Conn) *turnTCPPacketConn {
	return &turnTCPPacketConn{Conn: conn, reader: bufio.NewReaderSize(conn, turnTCPReaderSize)}
}

func turnTCPFrameSize(header []byte) (int, error) {
	if len(header) < 4 {
		return 0, io.ErrUnexpectedEOF
	}
	first := binary.BigEndian.Uint16(header[:2])
	payloadLen := int(binary.BigEndian.Uint16(header[2:4]))
	switch first & 0xc000 {
	case 0x0000: // STUN message.
		if len(header) < 8 || binary.BigEndian.Uint32(header[4:8]) != stunMagicCookie {
			return 0, errInvalidTURNFrame
		}
		return stunHeaderSize + payloadLen, nil
	case 0x4000: // TURN ChannelData, padded to a 4-byte boundary on TCP.
		return 4 + (payloadLen+3)&^3, nil
	default:
		return 0, errInvalidTURNFrame
	}
}

func (c *turnTCPPacketConn) ReadFrom(dst []byte) (int, net.Addr, error) {
	c.readMu.Lock()
	defer c.readMu.Unlock()

	header, err := c.reader.Peek(4)
	if err != nil {
		return 0, nil, err
	}
	if binary.BigEndian.Uint16(header[:2])&0xc000 == 0 {
		header, err = c.reader.Peek(8)
		if err != nil {
			return 0, nil, err
		}
	}
	size, err := turnTCPFrameSize(header)
	if err != nil {
		return 0, nil, err
	}
	if size > len(dst) {
		return 0, nil, errTURNFrameTooLarge
	}
	frame, err := c.reader.Peek(size)
	if err != nil {
		return 0, nil, err
	}
	copy(dst, frame)
	if _, err = c.reader.Discard(size); err != nil {
		return 0, nil, err
	}
	return size, c.RemoteAddr(), nil
}

func (c *turnTCPPacketConn) WriteTo(payload []byte, _ net.Addr) (int, error) {
	n, err := c.Conn.Write(payload)
	if err == nil && n != len(payload) {
		err = io.ErrShortWrite
	}
	return n, err
}

func (c *turnTCPPacketConn) SetDeadline(deadline time.Time) error {
	return c.Conn.SetDeadline(deadline)
}

func (c *turnTCPPacketConn) SetReadDeadline(deadline time.Time) error {
	return c.Conn.SetReadDeadline(deadline)
}

func (c *turnTCPPacketConn) SetWriteDeadline(deadline time.Time) error {
	return c.Conn.SetWriteDeadline(deadline)
}
