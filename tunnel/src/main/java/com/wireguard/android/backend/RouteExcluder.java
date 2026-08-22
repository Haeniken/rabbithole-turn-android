/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.backend;

import com.wireguard.config.InetNetwork;
import com.wireguard.config.ParseException;
import com.wireguard.util.NonNullForAll;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** CIDR range subtraction used on Android versions without VpnService.Builder.excludeRoute(). */
@NonNullForAll
final class RouteExcluder {
    private RouteExcluder() { }

    /** VpnService rejects loopback destinations even when they are valid IP prefixes. */
    static boolean isVpnServiceRouteSupported(final InetNetwork route) {
        return !route.getAddress().isLoopbackAddress();
    }

    /** Returns the exact part of {@code excluded} covered by {@code allowed}, without overlaps. */
    static List<InetNetwork> intersect(final Collection<InetNetwork> allowed,
                                       final Collection<InetNetwork> excluded) {
        final List<Range> intersections = new ArrayList<>();
        for (final InetNetwork allowedNetwork : allowed) {
            final Range allowedRange = Range.from(allowedNetwork);
            for (final InetNetwork excludedNetwork : excluded) {
                final Range excludedRange = Range.from(excludedNetwork);
                if (excludedRange.bits != allowedRange.bits ||
                        excludedRange.end.compareTo(allowedRange.start) < 0 ||
                        excludedRange.start.compareTo(allowedRange.end) > 0)
                    continue;
                intersections.add(new Range(
                        excludedRange.start.max(allowedRange.start),
                        excludedRange.end.min(allowedRange.end),
                        allowedRange.bits));
            }
        }
        return rangesToNetworks(mergeRanges(intersections));
    }

    static List<InetNetwork> exclude(final Collection<InetNetwork> allowed,
                                     final Collection<InetNetwork> excluded) {
        final List<InetNetwork> result = new ArrayList<>();
        for (final InetNetwork allowedNetwork : allowed) {
            final Range allowedRange = Range.from(allowedNetwork);
            final List<Range> intersections = new ArrayList<>();
            for (final InetNetwork excludedNetwork : excluded) {
                final Range excludedRange = Range.from(excludedNetwork);
                if (excludedRange.bits != allowedRange.bits ||
                        excludedRange.end.compareTo(allowedRange.start) < 0 ||
                        excludedRange.start.compareTo(allowedRange.end) > 0)
                    continue;
                intersections.add(new Range(
                        excludedRange.start.max(allowedRange.start),
                        excludedRange.end.min(allowedRange.end),
                        allowedRange.bits));
            }
            intersections.sort(Comparator.comparing(range -> range.start));

            BigInteger cursor = allowedRange.start;
            for (final Range excludedRange : intersections) {
                if (excludedRange.end.compareTo(cursor) < 0)
                    continue;
                if (excludedRange.start.compareTo(cursor) > 0)
                    appendRange(result, cursor, excludedRange.start.subtract(BigInteger.ONE), allowedRange.bits);
                cursor = cursor.max(excludedRange.end.add(BigInteger.ONE));
                if (cursor.compareTo(allowedRange.end) > 0)
                    break;
            }
            if (cursor.compareTo(allowedRange.end) <= 0)
                appendRange(result, cursor, allowedRange.end, allowedRange.bits);
        }
        return result;
    }

    private static void appendRange(final List<InetNetwork> output, BigInteger start,
                                    final BigInteger end, final int bits) {
        while (start.compareTo(end) <= 0) {
            final int alignmentBits = start.signum() == 0 ? bits : Math.min(start.getLowestSetBit(), bits);
            final BigInteger remaining = end.subtract(start).add(BigInteger.ONE);
            final int sizeBits = remaining.bitLength() - 1;
            final int hostBits = Math.min(alignmentBits, sizeBits);
            final int prefix = bits - hostBits;
            output.add(toNetwork(start, prefix, bits));
            start = start.add(BigInteger.ONE.shiftLeft(hostBits));
        }
    }

    private static List<Range> mergeRanges(final List<Range> input) {
        input.sort(Comparator.comparingInt((Range range) -> range.bits).thenComparing(range -> range.start));
        final List<Range> merged = new ArrayList<>();
        for (final Range range : input) {
            if (merged.isEmpty()) {
                merged.add(range);
                continue;
            }
            final Range previous = merged.get(merged.size() - 1);
            if (previous.bits == range.bits && range.start.compareTo(previous.end.add(BigInteger.ONE)) <= 0) {
                merged.set(merged.size() - 1, new Range(previous.start, previous.end.max(range.end), previous.bits));
            } else {
                merged.add(range);
            }
        }
        return merged;
    }

    private static List<InetNetwork> rangesToNetworks(final List<Range> ranges) {
        final List<InetNetwork> networks = new ArrayList<>();
        for (final Range range : ranges)
            appendRange(networks, range.start, range.end, range.bits);
        return networks;
    }

    private static InetNetwork toNetwork(final BigInteger address, final int prefix, final int bits) {
        final int byteCount = bits / 8;
        final byte[] raw = address.toByteArray();
        final byte[] normalized = new byte[byteCount];
        final int copyLength = Math.min(raw.length, byteCount);
        System.arraycopy(raw, raw.length - copyLength, normalized, byteCount - copyLength, copyLength);
        try {
            return InetNetwork.parse(InetAddress.getByAddress(normalized).getHostAddress() + '/' + prefix);
        } catch (final UnknownHostException | ParseException e) {
            throw new IllegalArgumentException("Unable to create a route", e);
        }
    }

    private static final class Range {
        final BigInteger start;
        final BigInteger end;
        final int bits;

        Range(final BigInteger start, final BigInteger end, final int bits) {
            this.start = start;
            this.end = end;
            this.bits = bits;
        }

        static Range from(final InetNetwork network) {
            final byte[] raw = network.getAddress().getAddress();
            final int bits = raw.length * 8;
            final int hostBits = bits - network.getMask();
            final BigInteger address = new BigInteger(1, raw);
            final BigInteger start = address.shiftRight(hostBits).shiftLeft(hostBits);
            final BigInteger end = start.add(BigInteger.ONE.shiftLeft(hostBits).subtract(BigInteger.ONE));
            return new Range(start, end, bits);
        }
    }
}
