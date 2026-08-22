/* SPDX-License-Identifier: Apache-2.0 */

package main

type captchaSolveMode int

const (
	captchaSolveModeAuto captchaSolveMode = iota
	captchaSolveModeSliderPOC
	captchaSolveModeManual
)

func captchaSolveModeForAttempt(attempt int, manualCaptcha bool, enableSliderPOC bool) (captchaSolveMode, bool) {
	// The current VK verification page is a client-rendered application. Let the
	// Android WebView handle it first: it can complete a no-interaction check by
	// itself and remains visible when VK actually requires user input.
	if manualCaptcha {
		if attempt == 0 {
			return captchaSolveModeManual, true
		}
		return 0, false
	}

	switch attempt {
	case 0:
		return captchaSolveModeAuto, true
	case 1:
		if enableSliderPOC {
			return captchaSolveModeSliderPOC, true
		}
	}

	return 0, false
}

func captchaSolveModeLabel(mode captchaSolveMode) string {
	switch mode {
	case captchaSolveModeAuto:
		return "auto captcha"
	case captchaSolveModeSliderPOC:
		return "auto captcha slider POC"
	case captchaSolveModeManual:
		return "manual captcha"
	default:
		return "captcha"
	}
}
