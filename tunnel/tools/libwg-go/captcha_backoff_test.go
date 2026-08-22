/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 WireGuard LLC. All Rights Reserved.
 */

package main

import (
	"testing"
	"time"
)

func TestCaptchaBackoffDuration(t *testing.T) {
	tests := []struct {
		failures int
		want     time.Duration
	}{
		{failures: 0, want: 30 * time.Second},
		{failures: 1, want: 30 * time.Second},
		{failures: 2, want: time.Minute},
		{failures: 3, want: 2 * time.Minute},
		{failures: 4, want: 4 * time.Minute},
		{failures: 5, want: 5 * time.Minute},
		{failures: 20, want: 5 * time.Minute},
	}

	for _, test := range tests {
		if got := captchaBackoffDuration(test.failures); got != test.want {
			t.Fatalf("failures=%d: got %v, want %v", test.failures, got, test.want)
		}
	}
}
