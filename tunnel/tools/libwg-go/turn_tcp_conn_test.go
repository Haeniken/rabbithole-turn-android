/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"bytes"
	"encoding/binary"
	"errors"
	"net"
	"testing"
)

func stunTestFrame(payload []byte) []byte {
	frame := make([]byte, stunHeaderSize+len(payload))
	binary.BigEndian.PutUint16(frame[2:4], uint16(len(payload)))
	binary.BigEndian.PutUint32(frame[4:8], stunMagicCookie)
	copy(frame[stunHeaderSize:], payload)
	return frame
}

func channelTestFrame(channel uint16, payload []byte) []byte {
	frame := make([]byte, 4+(len(payload)+3)&^3)
	binary.BigEndian.PutUint16(frame[:2], channel)
	binary.BigEndian.PutUint16(frame[2:4], uint16(len(payload)))
	copy(frame[4:], payload)
	return frame
}

func TestTurnTCPFrameSize(t *testing.T) {
	stun := stunTestFrame([]byte("abc"))
	if got, err := turnTCPFrameSize(stun[:8]); err != nil || got != len(stun) {
		t.Fatalf("STUN size=(%d,%v), want %d", got, err, len(stun))
	}
	channel := channelTestFrame(0x4001, []byte("abcde"))
	if got, err := turnTCPFrameSize(channel[:8]); err != nil || got != len(channel) {
		t.Fatalf("ChannelData size=(%d,%v), want %d", got, err, len(channel))
	}
}

func TestTurnTCPPacketConnReadsCoalescedFrames(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	first := stunTestFrame([]byte("one"))
	second := channelTestFrame(0x4001, []byte("two"))
	go func() {
		_, _ = server.Write(append(append([]byte{}, first...), second...))
	}()

	conn := newTurnTCPPacketConn(client)
	buf := make([]byte, 256)
	n, _, err := conn.ReadFrom(buf)
	if err != nil || !bytes.Equal(buf[:n], first) {
		t.Fatalf("first read=(%d,%v), data mismatch", n, err)
	}
	n, _, err = conn.ReadFrom(buf)
	if err != nil || !bytes.Equal(buf[:n], second) {
		t.Fatalf("second read=(%d,%v), data mismatch", n, err)
	}
}

func TestTurnTCPPacketConnDoesNotConsumeOversizedFrame(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	frame := stunTestFrame(bytes.Repeat([]byte{1}, 32))
	go func() { _, _ = server.Write(frame) }()
	conn := newTurnTCPPacketConn(client)
	if _, _, err := conn.ReadFrom(make([]byte, 8)); !errors.Is(err, errTURNFrameTooLarge) {
		t.Fatalf("small read error=%v, want %v", err, errTURNFrameTooLarge)
	}
	buf := make([]byte, 128)
	n, _, err := conn.ReadFrom(buf)
	if err != nil || !bytes.Equal(buf[:n], frame) {
		t.Fatalf("retry read=(%d,%v), data mismatch", n, err)
	}
}

var _ net.PacketConn = (*turnTCPPacketConn)(nil)
