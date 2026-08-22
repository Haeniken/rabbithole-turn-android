/* SPDX-License-Identifier: Apache-2.0 */

package main

import "testing"

func TestNextRoundRobinUsesOnlyReadyPool(t *testing.T) {
	ready := []int{2, 5, 9}
	want := []int{2, 5, 9, 2, 5, 9}
	var cursor uint64
	for i, expected := range want {
		got, ok := nextRoundRobin(ready, &cursor)
		if !ok || got != expected {
			t.Fatalf("selection %d: got %d (ok=%t), want %d", i, got, ok, expected)
		}
	}
}

func TestNextRoundRobinEmptyPool(t *testing.T) {
	cursor := uint64(7)
	if _, ok := nextRoundRobin([]int(nil), &cursor); ok {
		t.Fatal("empty pool unexpectedly returned an item")
	}
	if cursor != 7 {
		t.Fatalf("empty pool changed cursor to %d", cursor)
	}
}
