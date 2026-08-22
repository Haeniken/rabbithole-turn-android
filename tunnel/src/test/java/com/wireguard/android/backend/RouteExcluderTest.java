/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.backend;

import com.wireguard.config.InetNetwork;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

public class RouteExcluderTest {
    @Test
    public void rejectsLoopbackRoutesUnsupportedByVpnService() throws Exception {
        assertEquals(false, RouteExcluder.isVpnServiceRouteSupported(InetNetwork.parse("127.0.0.0/8")));
        assertEquals(false, RouteExcluder.isVpnServiceRouteSupported(InetNetwork.parse("::1/128")));
        assertEquals(true, RouteExcluder.isVpnServiceRouteSupported(InetNetwork.parse("203.0.113.0/24")));
    }

    @Test
    public void excludesMiddleOfIPv4DefaultRoute() throws Exception {
        final List<InetNetwork> routes = RouteExcluder.exclude(
                Collections.singletonList(InetNetwork.parse("0.0.0.0/0")),
                Collections.singletonList(InetNetwork.parse("10.0.0.0/8")));
        assertEquals(
                List.of("0.0.0.0/5", "8.0.0.0/7", "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4", "32.0.0.0/3", "64.0.0.0/2", "128.0.0.0/1"),
                routes.stream().map(InetNetwork::toString).collect(Collectors.toList()));
    }

    @Test
    public void ignoresExclusionOutsideAllowedRoute() throws Exception {
        final List<InetNetwork> routes = RouteExcluder.exclude(
                Collections.singletonList(InetNetwork.parse("192.0.2.0/24")),
                Collections.singletonList(InetNetwork.parse("198.51.100.0/24")));
        assertEquals(List.of("192.0.2.0/24"), routes.stream().map(InetNetwork::toString).collect(Collectors.toList()));
    }

    @Test
    public void intersectsAndCollapsesDirectRoutes() throws Exception {
        final List<InetNetwork> routes = RouteExcluder.intersect(
                Collections.singletonList(InetNetwork.parse("192.0.2.0/25")),
                List.of(
                        InetNetwork.parse("192.0.2.0/26"),
                        InetNetwork.parse("192.0.2.64/26"),
                        InetNetwork.parse("198.51.100.0/24")));
        assertEquals(List.of("192.0.2.0/25"), routes.stream().map(InetNetwork::toString).collect(Collectors.toList()));
    }
}
