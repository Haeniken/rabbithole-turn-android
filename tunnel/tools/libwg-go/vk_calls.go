/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 WireGuard LLC. All Rights Reserved.
 */

package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	neturl "net/url"
	"strings"

	fhttp "github.com/bogdanfinn/fhttp"
	"github.com/google/uuid"
	tlsclient "github.com/kiper292/tls-client"
)

const (
	vkCallsAPIBase    = "https://api.vk.me/method/"
	vkCallsAPIVersion = "5.276"
	vkConnectClientID = "8093730"
	vkCallJoinBase    = "https://vk.ru/call/join/"
)

// getVKCallsTokenChain implements the anonymous VK Calls API flow. It is kept
// separate from the browser-like legacy flow because mixing their headers,
// hosts and token types makes VK apply the legacy captcha gate.
func getVKCallsTokenChain(
	ctx context.Context,
	link string,
	client tlsclient.HttpClient,
	profile Profile,
) (string, string, string, error) {
	deviceID := uuid.New().String()
	name := generateName()
	joinURL := vkCallJoinBase + link

	request := func(endpoint string, values neturl.Values) (map[string]interface{}, error) {
		url := endpoint
		if len(values) > 0 {
			url += "?" + values.Encode()
		}
		parsedURL, err := neturl.Parse(url)
		if err != nil {
			return nil, fmt.Errorf("parse request URL: %w", err)
		}
		req, err := fhttp.NewRequestWithContext(ctx, "POST", url, bytes.NewReader(nil))
		if err != nil {
			return nil, err
		}
		req.Host = parsedURL.Hostname()
		req.Header.Set("User-Agent", profile.UserAgent)
		req.Header.Set("Accept", "*/*")
		req.Header.Set("Accept-Language", "en-GB,en;q=0.9")

		resp, err := client.Do(req)
		if err != nil {
			return nil, err
		}
		defer func() {
			if closeErr := resp.Body.Close(); closeErr != nil {
				turnLog("[VK Auth] Close VK Calls response: %v", closeErr)
			}
		}()
		body, err := io.ReadAll(resp.Body)
		if err != nil {
			return nil, err
		}
		decoder := json.NewDecoder(bytes.NewReader(body))
		decoder.UseNumber()
		var payload map[string]interface{}
		if err := decoder.Decode(&payload); err != nil {
			return nil, fmt.Errorf("decode VK Calls response: %w", err)
		}
		if err := vkCallsAPIError(payload); err != nil {
			return nil, err
		}
		return payload, nil
	}

	step1, err := request(vkCallsAPIBase+"auth.getAnonymToken", neturl.Values{
		"v":          {vkCallsAPIVersion},
		"client_id":  {vkConnectClientID},
		"link":       {joinURL},
		"device_id":  {deviceID},
		"anonymName": {name},
		"lang":       {"en"},
	})
	if err != nil {
		return "", "", "", fmt.Errorf("auth.getAnonymToken: %w", err)
	}
	anonymousToken, err := vkCallsString(step1, "response", "token")
	if err != nil {
		return "", "", "", fmt.Errorf("parse anonymous token: %w", err)
	}

	step2, err := request(vkCallsAPIBase+"messages.getCallPreview", neturl.Values{
		"v":               {vkCallsAPIVersion},
		"anonymous_token": {anonymousToken},
		"device_id":       {deviceID},
		"extended":        {"1"},
		"fields":          {"first_name,last_name,photo_200"},
		"lang":            {"en"},
		"link":            {joinURL},
	})
	if err != nil {
		return "", "", "", fmt.Errorf("messages.getCallPreview: %w", err)
	}
	userID, err := vkCallsNumberString(step2, "response", "user_id")
	if err != nil {
		return "", "", "", fmt.Errorf("parse anonymous user id: %w", err)
	}
	secret, err := vkCallsString(step2, "response", "secret")
	if err != nil {
		return "", "", "", fmt.Errorf("parse call secret: %w", err)
	}

	step3, err := request(vkCallsAPIBase+"messages.getAnonymCallToken", neturl.Values{
		"v":               {vkCallsAPIVersion},
		"anonymous_token": {anonymousToken},
		"device_id":       {deviceID},
		"link":            {joinURL},
		"name":            {name},
		"user_id":         {userID},
		"secret":          {secret},
		"lang":            {"en"},
	})
	if err != nil {
		return "", "", "", fmt.Errorf("messages.getAnonymCallToken: %w", err)
	}
	callToken, err := vkCallsString(step3, "response", "token")
	if err != nil {
		return "", "", "", fmt.Errorf("parse anonymous call token: %w", err)
	}

	sessionData, err := json.Marshal(map[string]interface{}{
		"version":        2,
		"device_id":      uuid.New().String(),
		"client_version": "1.0.1",
	})
	if err != nil {
		return "", "", "", fmt.Errorf("build OK session data: %w", err)
	}
	step4, err := request("https://calls.okcdn.ru/fb.do", neturl.Values{
		"session_data":    {string(sessionData)},
		"method":          {"auth.anonymLogin"},
		"format":          {"JSON"},
		"application_key": {"CGMMEJLGDIHBABABA"},
	})
	if err != nil {
		return "", "", "", fmt.Errorf("auth.anonymLogin: %w", err)
	}
	sessionKey, err := vkCallsString(step4, "session_key")
	if err != nil {
		return "", "", "", fmt.Errorf("parse OK session key: %w", err)
	}

	step5, err := request("https://calls.okcdn.ru/fb.do", neturl.Values{
		"joinLink":        {link},
		"isVideo":         {"false"},
		"protocolVersion": {"5"},
		"anonymToken":     {callToken},
		"method":          {"vchat.joinConversationByLink"},
		"format":          {"JSON"},
		"application_key": {"CGMMEJLGDIHBABABA"},
		"session_key":     {sessionKey},
	})
	if err != nil {
		return "", "", "", fmt.Errorf("vchat.joinConversationByLink: %w", err)
	}
	return parseVKCallsTURN(ctx, step5)
}

func vkCallsAPIError(payload map[string]interface{}) error {
	value, ok := payload["error"]
	if !ok || value == nil || value == "" {
		return nil
	}
	if message, ok := value.(string); ok {
		return fmt.Errorf("remote API error: %s", message)
	}
	errorMap, ok := value.(map[string]interface{})
	if !ok {
		return fmt.Errorf("remote API error")
	}
	code := "unknown"
	if rawCode, exists := errorMap["error_code"]; exists {
		code = fmt.Sprint(rawCode)
	}
	message := "request rejected"
	if rawMessage, exists := errorMap["error_msg"].(string); exists && rawMessage != "" {
		message = rawMessage
	}
	return fmt.Errorf("remote API error %s: %s", code, message)
}

func vkCallsValue(payload map[string]interface{}, keys ...string) (interface{}, error) {
	var current interface{} = payload
	for _, key := range keys {
		object, ok := current.(map[string]interface{})
		if !ok {
			return nil, fmt.Errorf("%s is not an object", strings.Join(keys, "."))
		}
		current, ok = object[key]
		if !ok {
			return nil, fmt.Errorf("%s is missing", strings.Join(keys, "."))
		}
	}
	return current, nil
}

func vkCallsString(payload map[string]interface{}, keys ...string) (string, error) {
	value, err := vkCallsValue(payload, keys...)
	if err != nil {
		return "", err
	}
	text, ok := value.(string)
	if !ok || text == "" {
		return "", fmt.Errorf("%s is not a non-empty string", strings.Join(keys, "."))
	}
	return text, nil
}

func vkCallsNumberString(payload map[string]interface{}, keys ...string) (string, error) {
	value, err := vkCallsValue(payload, keys...)
	if err != nil {
		return "", err
	}
	switch number := value.(type) {
	case json.Number:
		return number.String(), nil
	case float64:
		return fmt.Sprintf("%.0f", number), nil
	case string:
		if number != "" {
			return number, nil
		}
	}
	return "", fmt.Errorf("%s is not a number", strings.Join(keys, "."))
}

func parseVKCallsTURN(ctx context.Context, payload map[string]interface{}) (string, string, string, error) {
	username, err := vkCallsString(payload, "turn_server", "username")
	if err != nil {
		return "", "", "", err
	}
	credential, err := vkCallsString(payload, "turn_server", "credential")
	if err != nil {
		return "", "", "", err
	}
	urlsValue, err := vkCallsValue(payload, "turn_server", "urls")
	if err != nil {
		return "", "", "", err
	}
	urls, ok := urlsValue.([]interface{})
	if !ok || len(urls) == 0 {
		return "", "", "", fmt.Errorf("turn_server.urls is empty")
	}
	firstURL, ok := urls[0].(string)
	if !ok || firstURL == "" {
		return "", "", "", fmt.Errorf("turn_server.urls[0] is invalid")
	}
	address := strings.Split(firstURL, "?")[0]
	address = strings.TrimPrefix(strings.TrimPrefix(address, "turn:"), "turns:")
	host, port, splitErr := net.SplitHostPort(address)
	if splitErr == nil && net.ParseIP(host) == nil {
		resolvedIP, resolveErr := hostCache.Resolve(ctx, host)
		if resolveErr != nil {
			turnLog("[TURN DNS] Warning: failed to resolve TURN server %s: %v", host, resolveErr)
		} else {
			address = net.JoinHostPort(resolvedIP, port)
			turnLog("[TURN DNS] Resolved TURN server %s -> %s", host, resolvedIP)
		}
	}
	return username, credential, address, nil
}
