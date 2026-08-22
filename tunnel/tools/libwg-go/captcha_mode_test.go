/* SPDX-License-Identifier: Apache-2.0 */

package main

import "testing"

func TestCaptchaSolveModePrefersWebView(t *testing.T) {
	mode, ok := captchaSolveModeForAttempt(0, true, true)
	if !ok || mode != captchaSolveModeManual {
		t.Fatalf("expected WebView mode first, got mode=%v ok=%v", mode, ok)
	}
	if _, ok := captchaSolveModeForAttempt(1, true, true); ok {
		t.Fatal("expected no stale HTML fallback after WebView")
	}
}

func TestCaptchaSolveModeKeepsNonWebViewFallbacks(t *testing.T) {
	mode, ok := captchaSolveModeForAttempt(0, false, true)
	if !ok || mode != captchaSolveModeAuto {
		t.Fatalf("expected automatic mode, got mode=%v ok=%v", mode, ok)
	}
	mode, ok = captchaSolveModeForAttempt(1, false, true)
	if !ok || mode != captchaSolveModeSliderPOC {
		t.Fatalf("expected slider mode, got mode=%v ok=%v", mode, ok)
	}
}
