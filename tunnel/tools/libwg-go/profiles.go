package main

import (
	"encoding/json"
	"math/rand"
	"strings"
	"sync/atomic"
)

type Profile struct {
	UserAgent           string   `json:"userAgent"`
	SecChUa             string   `json:"secChUa"`
	SecChUaMobile       string   `json:"secChUaMobile"`
	SecChUaPlatform     string   `json:"secChUaPlatform"`
	Language            string   `json:"language"`
	Languages           []string `json:"languages"`
	NavigatorPlatform   string   `json:"navigatorPlatform"`
	ScreenWidth         int      `json:"screenWidth"`
	ScreenHeight        int      `json:"screenHeight"`
	ScreenAvailWidth    int      `json:"screenAvailWidth"`
	ScreenAvailHeight   int      `json:"screenAvailHeight"`
	InnerWidth          int      `json:"innerWidth"`
	InnerHeight         int      `json:"innerHeight"`
	DevicePixelRatio    float64  `json:"devicePixelRatio"`
	HardwareConcurrency int      `json:"hardwareConcurrency"`
	DeviceMemory        int      `json:"deviceMemory"`
}

var activeCaptchaProfile atomic.Value

func init() {
	activeCaptchaProfile.Store(defaultAndroidCaptchaProfile())
}

func defaultAndroidCaptchaProfile() Profile {
	return Profile{
		UserAgent:           "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36",
		SecChUa:             `"Not(A:Brand";v="99", "Google Chrome";v="146", "Chromium";v="146"`,
		SecChUaMobile:       "?1",
		SecChUaPlatform:     `"Android"`,
		Language:            "en-US",
		Languages:           []string{"en-US", "en"},
		NavigatorPlatform:   "Linux armv81",
		ScreenWidth:         412,
		ScreenHeight:        915,
		ScreenAvailWidth:    412,
		ScreenAvailHeight:   915,
		InnerWidth:          412,
		InnerHeight:         915,
		DevicePixelRatio:    2.625,
		HardwareConcurrency: 8,
		DeviceMemory:        8,
	}
}

func normalizeCaptchaProfile(value Profile) Profile {
	fallback := defaultAndroidCaptchaProfile()
	if strings.TrimSpace(value.UserAgent) == "" {
		value.UserAgent = fallback.UserAgent
	}
	if strings.TrimSpace(value.SecChUa) == "" {
		value.SecChUa = fallback.SecChUa
	}
	if strings.TrimSpace(value.SecChUaMobile) == "" {
		value.SecChUaMobile = fallback.SecChUaMobile
	}
	if strings.TrimSpace(value.SecChUaPlatform) == "" {
		value.SecChUaPlatform = fallback.SecChUaPlatform
	}
	if strings.TrimSpace(value.Language) == "" {
		value.Language = fallback.Language
	}
	if len(value.Languages) == 0 {
		value.Languages = append([]string(nil), fallback.Languages...)
	}
	if strings.TrimSpace(value.NavigatorPlatform) == "" {
		value.NavigatorPlatform = fallback.NavigatorPlatform
	}
	if value.ScreenWidth <= 0 {
		value.ScreenWidth = fallback.ScreenWidth
	}
	if value.ScreenHeight <= 0 {
		value.ScreenHeight = fallback.ScreenHeight
	}
	if value.ScreenAvailWidth <= 0 {
		value.ScreenAvailWidth = value.ScreenWidth
	}
	if value.ScreenAvailHeight <= 0 {
		value.ScreenAvailHeight = value.ScreenHeight
	}
	if value.InnerWidth <= 0 {
		value.InnerWidth = value.ScreenWidth
	}
	if value.InnerHeight <= 0 {
		value.InnerHeight = value.ScreenHeight
	}
	if value.DevicePixelRatio <= 0 {
		value.DevicePixelRatio = fallback.DevicePixelRatio
	}
	if value.HardwareConcurrency <= 0 {
		value.HardwareConcurrency = fallback.HardwareConcurrency
	}
	if value.DeviceMemory <= 0 {
		value.DeviceMemory = fallback.DeviceMemory
	}
	return value
}

func setCaptchaProfileJSON(raw string) {
	value := defaultAndroidCaptchaProfile()
	if strings.TrimSpace(raw) != "" {
		if err := json.Unmarshal([]byte(raw), &value); err != nil {
			turnLog("[Captcha] Invalid Android browser profile, using fallback: %v", err)
			value = defaultAndroidCaptchaProfile()
		}
	}
	activeCaptchaProfile.Store(normalizeCaptchaProfile(value))
}

func getCaptchaProfile() Profile {
	return activeCaptchaProfile.Load().(Profile)
}

// profiles contain paired User-Agent and Client Hints strings to harden bot detection.
var profile = []Profile{
	// Windows Chrome
	{
		UserAgent:       "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
		SecChUa:         `"Chromium";v="146", "Not-A.Brand";v="24", "Google Chrome";v="146"`,
		SecChUaMobile:   "?0",
		SecChUaPlatform: `"Windows"`,
	},
	{
		UserAgent:       "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36",
		SecChUa:         `"Chromium";v="145", "Not-A.Brand";v="99", "Google Chrome";v="145"`,
		SecChUaMobile:   "?0",
		SecChUaPlatform: `"Windows"`,
	},
	{
		UserAgent:       "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36",
		SecChUa:         `"Chromium";v="144", "Not-A.Brand";v="8", "Google Chrome";v="144"`,
		SecChUaMobile:   "?0",
		SecChUaPlatform: `"Windows"`,
	},

	// Windows Edge
	{
		UserAgent:       "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36 Edg/146.0.0.0",
		SecChUa:         `"Chromium";v="146", "Not-A.Brand";v="24", "Microsoft Edge";v="146"`,
		SecChUaMobile:   "?0",
		SecChUaPlatform: `"Windows"`,
	},
	{
		UserAgent:       "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36 Edg/145.0.0.0",
		SecChUa:         `"Chromium";v="145", "Not-A.Brand";v="99", "Microsoft Edge";v="145"`,
		SecChUaMobile:   "?0",
		SecChUaPlatform: `"Windows"`,
	},

	// macOS Chrome
	{
		UserAgent:       "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
		SecChUa:         `"Chromium";v="146", "Not-A.Brand";v="24", "Google Chrome";v="146"`,
		SecChUaMobile:   "?0",
		SecChUaPlatform: `"macOS"`,
	},
	{
		UserAgent:       "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36",
		SecChUa:         `"Chromium";v="145", "Not-A.Brand";v="99", "Google Chrome";v="145"`,
		SecChUaMobile:   "?0",
		SecChUaPlatform: `"macOS"`,
	},

	// Linux Chrome
	{
		UserAgent:       "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
		SecChUa:         `"Chromium";v="146", "Not-A.Brand";v="24", "Google Chrome";v="146"`,
		SecChUaMobile:   "?0",
		SecChUaPlatform: `"Linux"`,
	},
	{
		UserAgent:       "Mozilla/5.0 (X11; Ubuntu; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36",
		SecChUa:         `"Chromium";v="144", "Not-A.Brand";v="8", "Google Chrome";v="144"`,
		SecChUaMobile:   "?0",
		SecChUaPlatform: `"Linux"`,
	},
}

// getRandomProfile returns a paired User-Agent and Client Hints profile.
func getRandomProfile() Profile {
	return profile[rand.Intn(len(profile))]
}
