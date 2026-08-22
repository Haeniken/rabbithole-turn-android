/* SPDX-License-Identifier: Apache-2.0 */

package main

// nextRoundRobin returns one item from the current ready pool and advances the
// cursor. The pool may change size between calls; modulo keeps the cursor valid
// without favoring any configured-but-unavailable stream slots.
func nextRoundRobin[T any](items []T, cursor *uint64) (T, bool) {
	var zero T
	if len(items) == 0 {
		return zero, false
	}
	item := items[*cursor%uint64(len(items))]
	(*cursor)++
	return item, true
}
