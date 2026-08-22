/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 WireGuard LLC. All Rights Reserved.
 */

package main

import "testing"

func TestNormalizeStreamsPerCred(t *testing.T) {
	tests := []struct {
		requested int
		want      int
	}{
		{requested: -1, want: 1},
		{requested: 0, want: 1},
		{requested: 1, want: 1},
		{requested: 4, want: 4},
		{requested: 10, want: 10},
		{requested: 16, want: 10},
	}

	for _, test := range tests {
		if got := normalizeStreamsPerCred(test.requested); got != test.want {
			t.Fatalf("requested=%d: got %d, want %d", test.requested, got, test.want)
		}
	}
}
