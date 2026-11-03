/* SPDX-License-Identifier: Apache-2.0 */
package main

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestSerializeFetchCancelledBeforeAdmission(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	called := false
	_, _, _, err := serializeFetch(ctx, "link", func(context.Context, string) (string, string, string, error) {
		called = true
		return "", "", "", nil
	})
	if !errors.Is(err, context.Canceled) || called {
		t.Fatal("cancelled request reached the provider")
	}
}

func TestSerializeFetchQueuedCancellationDoesNotWaitForActiveRequest(t *testing.T) {
	entered, release, finished := make(chan struct{}), make(chan struct{}), make(chan struct{})
	go func() {
		defer close(finished)
		serializeFetch(context.Background(), "active", func(context.Context, string) (string, string, string, error) {
			close(entered)
			<-release
			return "", "", "", nil
		})
	}()
	<-entered
	defer func() { close(release); <-finished }()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	var called atomic.Bool
	go func() {
		_, _, _, err := serializeFetch(ctx, "queued", func(context.Context, string) (string, string, string, error) {
			called.Store(true)
			return "", "", "", nil
		})
		done <- err
	}()
	select {
	case <-done:
		t.Fatal("queued request ran concurrently with active request")
	case <-time.After(20 * time.Millisecond):
	}
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) || called.Load() {
			t.Fatal("cancelled queue entry reached the provider")
		}
	case <-time.After(time.Second):
		t.Fatal("cancelled queue entry waited for the active fetch")
	}
}

func TestSerializeFetchStillAllowsOnlyOneActiveRequest(t *testing.T) {
	var active, completed atomic.Int32
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	var wg sync.WaitGroup
	for i := 0; i < 32; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, _, _, err := serializeFetch(ctx, "link", func(context.Context, string) (string, string, string, error) {
				if active.Add(1) != 1 {
					t.Error("provider calls overlapped")
				}
				defer active.Add(-1)
				time.Sleep(time.Millisecond)
				completed.Add(1)
				return "u", "p", "a", nil
			})
			if err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if completed.Load() != 32 || active.Load() != 0 {
		t.Fatal("lost requests or leaked admission permit")
	}
}

func TestSerializeFetchReleasesAdmissionAfterErrorAndPanic(t *testing.T) {
	want := errors.New("provider failure")
	_, _, _, err := serializeFetch(context.Background(), "link", func(context.Context, string) (string, string, string, error) { return "", "", "", want })
	if !errors.Is(err, want) {
		t.Fatal("provider error changed")
	}
	func() {
		defer func() {
			if recover() == nil {
				t.Error("expected test panic")
			}
		}()
		serializeFetch(context.Background(), "link", func(context.Context, string) (string, string, string, error) { panic("test") })
	}()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	u, _, _, err := serializeFetch(ctx, "link", func(context.Context, string) (string, string, string, error) { return "ok", "", "", nil })
	if err != nil || u != "ok" {
		t.Fatal("failed request leaked the admission permit")
	}
}

func TestSerializeFetchActiveCancellationDoesNotAdmitAnOverlappingFetch(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	entered, release, done := make(chan struct{}), make(chan struct{}), make(chan struct{})
	go func() {
		defer close(done)
		serializeFetch(ctx, "active", func(context.Context, string) (string, string, string, error) {
			close(entered)
			<-release // Simulate provider cleanup that takes time after cancel.
			return "", "", "", ctx.Err()
		})
	}()
	<-entered
	cancel()
	defer func() { close(release); <-done }()
	queued, stopWaiting := context.WithTimeout(context.Background(), 30*time.Millisecond)
	defer stopWaiting()
	called := false
	_, _, _, err := serializeFetch(queued, "next", func(context.Context, string) (string, string, string, error) { called = true; return "", "", "", nil })
	if !errors.Is(err, context.DeadlineExceeded) || called {
		t.Fatal("active cancellation allowed overlapping provider calls")
	}
}
