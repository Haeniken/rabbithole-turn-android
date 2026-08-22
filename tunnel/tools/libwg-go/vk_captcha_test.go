/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 WireGuard LLC. All Rights Reserved.
 */

package main

import (
	"encoding/json"
	"testing"
)

func TestParseVkCaptchaErrorAcceptsNotRobotRedirectOnly(t *testing.T) {
	captchaErr := ParseVkCaptchaError(map[string]interface{}{
		"error_code":         float64(14),
		"error_msg":          "Captcha need",
		"is_enabled_captcha": true,
		"redirect_uri":       "https://id.vk.ru/not_robot_captcha?domain=vk.com&session_token=test-token&variant=popup&blank=1",
	})

	if captchaErr == nil {
		t.Fatal("expected captcha error")
	}
	if !captchaErr.IsCaptchaError() {
		t.Fatal("expected IsCaptchaError to be true")
	}
	if captchaErr.SessionToken != "test-token" {
		t.Fatalf("unexpected session token: %q", captchaErr.SessionToken)
	}
	if captchaErr.CaptchaSid != "" {
		t.Fatalf("expected empty legacy captcha sid, got %q", captchaErr.CaptchaSid)
	}
	if captchaErr.CaptchaImg != "" {
		t.Fatalf("expected empty legacy captcha image, got %q", captchaErr.CaptchaImg)
	}
}

func TestParseVkCaptchaErrorKeepsLegacyFields(t *testing.T) {
	captchaErr := ParseVkCaptchaError(map[string]interface{}{
		"error_code":                 float64(14),
		"error_msg":                  "Captcha need",
		"redirect_uri":               "https://id.vk.ru/not_robot_captcha?session_token=test-token",
		"captcha_sid":                float64(12345),
		"captcha_img":                "https://example.com/captcha.jpg",
		"is_sound_captcha_available": true,
		"captcha_ts":                 float64(67890),
		"captcha_attempt":            "2",
	})

	if captchaErr == nil {
		t.Fatal("expected captcha error")
	}
	if captchaErr.CaptchaSid != "12345" {
		t.Fatalf("unexpected captcha sid: %q", captchaErr.CaptchaSid)
	}
	if captchaErr.CaptchaImg != "https://example.com/captcha.jpg" {
		t.Fatalf("unexpected captcha image: %q", captchaErr.CaptchaImg)
	}
	if !captchaErr.IsSoundCaptchaAvailable {
		t.Fatal("expected sound captcha flag")
	}
	if captchaErr.CaptchaTs != "67890" {
		t.Fatalf("unexpected captcha_ts: %q", captchaErr.CaptchaTs)
	}
	if captchaErr.CaptchaAttempt != "2" {
		t.Fatalf("unexpected captcha_attempt: %q", captchaErr.CaptchaAttempt)
	}
}

func TestCaptchaDeviceJSONUsesAndroidProfile(t *testing.T) {
	profile := normalizeCaptchaProfile(Profile{
		UserAgent:           "android-test-agent",
		SecChUa:             `"Chromium";v="146"`,
		SecChUaMobile:       "?1",
		SecChUaPlatform:     `"Android"`,
		Language:            "ru-RU",
		Languages:           []string{"ru-RU", "ru"},
		NavigatorPlatform:   "Linux armv81",
		ScreenWidth:         393,
		ScreenHeight:        873,
		ScreenAvailWidth:    393,
		ScreenAvailHeight:   873,
		InnerWidth:          393,
		InnerHeight:         873,
		DevicePixelRatio:    2.75,
		HardwareConcurrency: 8,
		DeviceMemory:        8,
	})

	var device map[string]interface{}
	if err := json.Unmarshal([]byte(buildCaptchaDeviceJSON(profile)), &device); err != nil {
		t.Fatalf("invalid device JSON: %v", err)
	}
	if got := device["platform"]; got != "Linux armv81" {
		t.Fatalf("unexpected platform: %v", got)
	}
	if got := device["userAgent"]; got != "android-test-agent" {
		t.Fatalf("unexpected user agent: %v", got)
	}
	if got := device["screenWidth"]; got != float64(393) {
		t.Fatalf("unexpected screen width: %v", got)
	}
}
