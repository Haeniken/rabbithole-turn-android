/* SPDX-License-Identifier: MIT */

package main

import (
	"crypto/cipher"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"sync/atomic"

	"golang.org/x/crypto/chacha20poly1305"
)

const (
	wrapKeyLen     = 32
	wrapRTPHdrLen  = 12
	wrapNonceLen   = 12
	wrapTagLen     = 16
	wrapHeaderLen  = wrapRTPHdrLen + wrapNonceLen
	wrapOverhead   = wrapHeaderLen + wrapTagLen
	wrapRTPVersion = 0x80
	wrapRTPPT      = 0x6F
	wrapTSStep     = 960
)

type wrapConn struct {
	aead      cipher.AEAD
	sessionID [4]byte
	ssrc      [4]byte
	counter   atomic.Uint64
	seq       atomic.Uint32
	timestamp atomic.Uint32
}

func newWrapConn(key []byte, isServer bool) (*wrapConn, error) {
	if len(key) != wrapKeyLen {
		return nil, fmt.Errorf("wrap: key must be %d bytes (got %d)", wrapKeyLen, len(key))
	}
	aead, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, fmt.Errorf("wrap: aead init: %w", err)
	}
	w := &wrapConn{aead: aead}

	var rnd [16]byte
	if _, err := rand.Read(rnd[:]); err != nil {
		return nil, fmt.Errorf("wrap: rand init: %w", err)
	}
	copy(w.sessionID[:], rnd[0:4])
	copy(w.ssrc[:], rnd[4:8])
	if isServer {
		w.sessionID[0] |= 0x80
		w.ssrc[0] |= 0x80
	} else {
		w.sessionID[0] &^= 0x80
		w.ssrc[0] &^= 0x80
	}
	w.seq.Store(uint32(binary.BigEndian.Uint16(rnd[8:10])))
	w.timestamp.Store(binary.BigEndian.Uint32(rnd[10:14]))

	var cb [8]byte
	if _, err := rand.Read(cb[:]); err != nil {
		return nil, fmt.Errorf("wrap: counter rand: %w", err)
	}
	w.counter.Store(binary.BigEndian.Uint64(cb[:]))
	return w, nil
}

func wrapMaxWire(payloadLen int) int {
	return wrapOverhead + payloadLen
}

func (w *wrapConn) wrapInto(dst, payload []byte) (int, error) {
	wireLen := wrapOverhead + len(payload)
	if len(dst) < wireLen {
		return 0, errors.New("wrap: dst buffer too small")
	}

	dst[0] = wrapRTPVersion
	dst[1] = wrapRTPPT
	seq := uint16(w.seq.Add(1) - 1)
	binary.BigEndian.PutUint16(dst[2:4], seq)
	ts := w.timestamp.Add(wrapTSStep) - wrapTSStep
	binary.BigEndian.PutUint32(dst[4:8], ts)
	copy(dst[8:12], w.ssrc[:])

	noncePos := wrapRTPHdrLen
	copy(dst[noncePos:noncePos+4], w.sessionID[:])
	ctr := w.counter.Add(1) - 1
	binary.BigEndian.PutUint64(dst[noncePos+4:noncePos+wrapNonceLen], ctr)

	nonce := dst[noncePos : noncePos+wrapNonceLen]
	aad := dst[:wrapHeaderLen]
	ctPos := wrapHeaderLen
	copy(dst[ctPos:], payload)
	w.aead.Seal(dst[ctPos:ctPos], nonce, dst[ctPos:ctPos+len(payload)], aad)

	return wireLen, nil
}

func (w *wrapConn) unwrapPacket(wire, dst []byte) (int, error) {
	if len(wire) < wrapOverhead {
		return 0, errors.New("wrap: packet too short")
	}
	nonce := wire[wrapRTPHdrLen : wrapRTPHdrLen+wrapNonceLen]
	aad := wire[:wrapHeaderLen]
	ct := wire[wrapHeaderLen:]

	plain, err := w.aead.Open(ct[:0], nonce, ct, aad)
	if err != nil {
		return 0, fmt.Errorf("wrap: AEAD open: %w", err)
	}
	if len(plain) > len(dst) {
		return 0, errors.New("wrap: dst buffer too small")
	}
	copy(dst[:len(plain)], plain)
	return len(plain), nil
}
