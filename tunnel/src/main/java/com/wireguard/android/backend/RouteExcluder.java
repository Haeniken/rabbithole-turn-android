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
import java.util.PriorityQueue;

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

    /**
     * Reduces a route set to at most {@code maxRoutes} entries for Android's VPN Binder call.
     *
     * <p>The result is a superset of the input. Neighboring prefixes are joined in order of the
     * smallest additional address range, so every requested direct destination remains direct
     * while the Binder transaction stays bounded.</p>
     */
    static List<InetNetwork> compactForVpnService(final Collection<InetNetwork> routes,
                                                   final int maxRoutes) {
        if (maxRoutes <= 0)
            throw new IllegalArgumentException("maxRoutes must be positive");
        final List<InetNetwork> normalized = rangesToNetworks(mergeRanges(toRanges(routes)));
        if (normalized.size() <= maxRoutes)
            return normalized;

        final List<InetNetwork> ipv4 = new ArrayList<>();
        final List<InetNetwork> ipv6 = new ArrayList<>();
        for (final InetNetwork route : normalized) {
            if (route.getAddress().getAddress().length == 4)
                ipv4.add(route);
            else
                ipv6.add(route);
        }
        final int families = (ipv4.isEmpty() ? 0 : 1) + (ipv6.isEmpty() ? 0 : 1);
        if (maxRoutes < families)
            throw new IllegalArgumentException("maxRoutes is smaller than the number of address families");
        if (ipv4.isEmpty())
            return compactFamily(ipv6, maxRoutes);
        if (ipv6.isEmpty())
            return compactFamily(ipv4, maxRoutes);

        int ipv4Limit = Math.max(1, (int) ((long) maxRoutes * ipv4.size() / normalized.size()));
        int ipv6Limit = maxRoutes - ipv4Limit;
        if (ipv6Limit == 0) {
            ipv6Limit = 1;
            --ipv4Limit;
        }
        if (ipv4.size() < ipv4Limit) {
            ipv6Limit += ipv4Limit - ipv4.size();
            ipv4Limit = ipv4.size();
        } else if (ipv6.size() < ipv6Limit) {
            ipv4Limit += ipv6Limit - ipv6.size();
            ipv6Limit = ipv6.size();
        }

        final List<InetNetwork> compacted = new ArrayList<>(maxRoutes);
        compacted.addAll(compactFamily(ipv4, ipv4Limit));
        compacted.addAll(compactFamily(ipv6, ipv6Limit));
        return compacted;
    }

    private static List<Range> toRanges(final Collection<InetNetwork> routes) {
        final List<Range> ranges = new ArrayList<>(routes.size());
        for (final InetNetwork route : routes)
            ranges.add(Range.from(route));
        return ranges;
    }

    private static List<InetNetwork> compactFamily(final List<InetNetwork> routes, final int limit) {
        if (routes.size() <= limit)
            return new ArrayList<>(routes);

        CompactNode head = null;
        CompactNode tail = null;
        for (final InetNetwork route : routes) {
            final CompactNode node = new CompactNode(Range.from(route));
            if (head == null)
                head = node;
            if (tail != null) {
                tail.next = node;
                node.previous = tail;
            }
            tail = node;
        }

        final PriorityQueue<CompactCandidate> candidates = new PriorityQueue<>();
        for (CompactNode node = head; node != null && node.next != null; node = node.next)
            candidates.add(new CompactCandidate(node, node.next));

        int count = routes.size();
        while (count > limit) {
            CompactCandidate candidate;
            do {
                candidate = candidates.poll();
                if (candidate == null)
                    throw new IllegalStateException("Unable to compact VPN routes");
            } while (!candidate.isValid());

            CompactNode first = candidate.left;
            CompactNode last = candidate.right;
            Range cover = Range.cover(first.range, last.range);
            boolean expanded;
            do {
                expanded = false;
                while (first.previous != null && first.previous.range.end.compareTo(cover.start) >= 0) {
                    first = first.previous;
                    expanded = true;
                }
                while (last.next != null && last.next.range.start.compareTo(cover.end) <= 0) {
                    last = last.next;
                    expanded = true;
                }
                if (expanded)
                    cover = Range.cover(first.range, last.range);
            } while (expanded);

            final CompactNode before = first.previous;
            final CompactNode after = last.next;
            int replaced = 0;
            CompactNode cursor = first;
            while (true) {
                cursor.active = false;
                ++cursor.version;
                ++replaced;
                if (cursor == last)
                    break;
                cursor = cursor.next;
            }

            final CompactNode merged = new CompactNode(cover);
            merged.previous = before;
            merged.next = after;
            if (before == null)
                head = merged;
            else
                before.next = merged;
            if (after != null)
                after.previous = merged;
            count -= replaced - 1;

            if (before != null)
                candidates.add(new CompactCandidate(before, merged));
            if (after != null)
                candidates.add(new CompactCandidate(merged, after));
        }

        final List<InetNetwork> compacted = new ArrayList<>(count);
        for (CompactNode node = head; node != null; node = node.next)
            compacted.add(toNetwork(node.range.start, node.range.prefix(), node.range.bits));
        return compacted;
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

        static Range cover(final Range first, final Range last) {
            if (first.bits != last.bits)
                throw new IllegalArgumentException("Cannot merge different address families");
            final BigInteger different = first.start.xor(last.end);
            final int prefix = first.bits - different.bitLength();
            final int hostBits = first.bits - prefix;
            final BigInteger start = first.start.shiftRight(hostBits).shiftLeft(hostBits);
            final BigInteger end = start.add(BigInteger.ONE.shiftLeft(hostBits).subtract(BigInteger.ONE));
            return new Range(start, end, first.bits);
        }

        int prefix() {
            return bits - size().getLowestSetBit();
        }

        BigInteger size() {
            return end.subtract(start).add(BigInteger.ONE);
        }
    }

    private static final class CompactNode {
        final Range range;
        CompactNode previous;
        CompactNode next;
        long version;
        boolean active = true;

        CompactNode(final Range range) {
            this.range = range;
        }
    }

    private static final class CompactCandidate implements Comparable<CompactCandidate> {
        final CompactNode left;
        final CompactNode right;
        final long leftVersion;
        final long rightVersion;
        final BigInteger addedAddresses;
        final int resultPrefix;

        CompactCandidate(final CompactNode left, final CompactNode right) {
            this.left = left;
            this.right = right;
            leftVersion = left.version;
            rightVersion = right.version;
            final Range cover = Range.cover(left.range, right.range);
            addedAddresses = cover.size().subtract(left.range.size()).subtract(right.range.size());
            resultPrefix = cover.prefix();
        }

        boolean isValid() {
            return left.active && right.active && left.version == leftVersion &&
                    right.version == rightVersion && left.next == right && right.previous == left;
        }

        @Override
        public int compareTo(final CompactCandidate other) {
            final int costComparison = addedAddresses.compareTo(other.addedAddresses);
            if (costComparison != 0)
                return costComparison;
            return Integer.compare(other.resultPrefix, resultPrefix);
        }
    }
}
