/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 WireGuard LLC. All Rights Reserved.
 */

package main

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"
)

const (
	captchaBackoffInitial = 30 * time.Second
	captchaBackoffMaximum = 5 * time.Minute
)

var errCaptchaWaitRequired = errors.New("CAPTCHA_WAIT_REQUIRED")

type captchaBackoffController struct {
	mu         sync.Mutex
	failures   int
	retryAfter time.Time
}

var captchaBackoff captchaBackoffController

func captchaBackoffDuration(failures int) time.Duration {
	if failures <= 1 {
		return captchaBackoffInitial
	}
	delay := captchaBackoffInitial
	for attempt := 1; attempt < failures && delay < captchaBackoffMaximum; attempt++ {
		delay *= 2
		if delay >= captchaBackoffMaximum {
			return captchaBackoffMaximum
		}
	}
	return delay
}

func (c *captchaBackoffController) registerFailure(reason string) error {
	c.mu.Lock()
	c.failures++
	delay := captchaBackoffDuration(c.failures)
	c.retryAfter = time.Now().Add(delay)
	c.mu.Unlock()

	turnLog("[Captcha] Solver failed; next authorization attempt in %v", delay)
	return fmt.Errorf("%w: %s", errCaptchaWaitRequired, reason)
}

func (c *captchaBackoffController) remaining() time.Duration {
	c.mu.Lock()
	defer c.mu.Unlock()
	remaining := time.Until(c.retryAfter)
	if remaining < 0 {
		return 0
	}
	return remaining
}

func (c *captchaBackoffController) wait(ctx context.Context) error {
	remaining := c.remaining()
	if remaining <= 0 {
		return nil
	}

	turnLog("[Captcha] Backoff active; waiting %v before retry", remaining.Round(time.Second))
	timer := time.NewTimer(remaining)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

func (c *captchaBackoffController) reset() {
	c.mu.Lock()
	hadFailures := c.failures > 0
	c.failures = 0
	c.retryAfter = time.Time{}
	c.mu.Unlock()
	if hadFailures {
		turnLog("[Captcha] Authorization succeeded; retry backoff reset")
	}
}
