/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.backend;

import com.wireguard.config.InetNetwork;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void compactionPreservesEveryRequestedDestination() throws Exception {
        final List<InetNetwork> routes = new ArrayList<>();
        for (int address = 0; address < 100; ++address)
            routes.add(InetNetwork.parse("198.18.0." + address + "/32"));

        final List<InetNetwork> compacted = RouteExcluder.compactForVpnService(routes, 12);

        assertTrue(compacted.size() <= 12);
        for (final InetNetwork route : routes)
            assertEquals(List.of(route), RouteExcluder.intersect(List.of(route), compacted));
    }

    @Test
    public void compactionKeepsAddressFamiliesSeparate() throws Exception {
        final List<InetNetwork> compacted = RouteExcluder.compactForVpnService(
                List.of(
                        InetNetwork.parse("192.0.2.0/32"),
                        InetNetwork.parse("192.0.2.3/32"),
                        InetNetwork.parse("2001:db8::/128"),
                        InetNetwork.parse("2001:db8::3/128")),
                2);

        assertEquals(
                List.of("192.0.2.0/30", "2001:db8:0:0:0:0:0:0/126"),
                compacted.stream().map(InetNetwork::toString).collect(Collectors.toList()));
    }

    @Test
    public void compactsObservedDragonRouteVolumeBelowBinderBudget() throws Exception {
        final List<InetNetwork> routes = new ArrayList<>();
        for (int index = 0; index < 13_149; ++index) {
            final int address = index * 2;
            routes.add(InetNetwork.parse(
                    "198.18." + (address >>> 8) + '.' + (address & 0xff) + "/32"));
        }

        final List<InetNetwork> compacted = RouteExcluder.compactForVpnService(routes, 7_500);

        assertTrue(compacted.size() <= 7_500);
        assertEquals(
                List.of(routes.get(0)),
                RouteExcluder.intersect(List.of(routes.get(0)), compacted));
        assertEquals(
                List.of(routes.get(routes.size() - 1)),
                RouteExcluder.intersect(List.of(routes.get(routes.size() - 1)), compacted));
    }
}
