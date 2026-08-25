/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.backend;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.IpPrefix;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;

import com.wireguard.android.backend.BackendException.Reason;
import com.wireguard.android.backend.Tunnel.State;
import com.wireguard.android.util.SharedLibraryLoader;
import com.wireguard.config.Config;
import com.wireguard.config.InetEndpoint;
import com.wireguard.config.InetNetwork;
import com.wireguard.config.Peer;
import com.wireguard.crypto.Key;
import com.wireguard.crypto.KeyFormatException;
import com.wireguard.util.NonNullForAll;

import java.net.InetAddress;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.annotation.Nullable;
import androidx.collection.ArraySet;

/**
 * Implementation of {@link Backend} that uses the wireguard-go userspace implementation to provide
 * WireGuard tunnels.
 */
@NonNullForAll
public final class GoBackend implements Backend {
    private static final int DNS_RESOLUTION_RETRIES = 10;
    // The observed Binder payload for 13,149 IpPrefix objects exceeded 1 MiB. Keep ample room for
    // addresses, DNS, app rules and device-specific parcel overhead.
    private static final int MAX_VPN_EXCLUDED_ROUTES = 7_500;
    private static final String TAG = "WireGuard/GoBackend";
    private static final Object VPN_SERVICE_LOCK = new Object();
    @Nullable private static volatile AlwaysOnCallback alwaysOnCallback;
    @Nullable private static volatile VpnServiceLifecycleCallback vpnServiceLifecycleCallback;
    private static CompletableFuture<VpnService> vpnService = new CompletableFuture<>();
    @Nullable private static volatile VpnService activeVpnService;
    private final Context context;
    @Nullable private Config currentConfig;
    @Nullable private Tunnel currentTunnel;
    private int currentTunnelHandle = -1;
    private List<InetNetwork> excludedRoutes = Collections.emptyList();
    private Set<String> globallyExcludedApplications = Collections.emptySet();

    /**
     * Public constructor for GoBackend.
     *
     * @param context An Android {@link Context}
     */
    public GoBackend(final Context context) {
        SharedLibraryLoader.loadSharedLibrary(context, "wg-go");
        this.context = context;
    }

    /** Sets destination networks that must use the physical network instead of this VPN. */
    public void setExcludedRoutes(final Collection<InetNetwork> routes) {
        excludedRoutes = Collections.unmodifiableList(new ArrayList<>(routes));
    }

    /** Sets applications that must use the physical network for every userspace tunnel. */
    public void setGloballyExcludedApplications(final Collection<String> applications) {
        globallyExcludedApplications = Collections.unmodifiableSet(new LinkedHashSet<>(applications));
    }

    /**
     * Set a {@link AlwaysOnCallback} to be invoked when {@link VpnService} is started by the
     * system's Always-On VPN mode.
     *
     * @param cb Callback to be invoked
     */
    public static void setAlwaysOnCallback(final AlwaysOnCallback cb) {
        alwaysOnCallback = cb;
    }

    /** Registers a process callback for unexpected VpnService termination. */
    public static void setVpnServiceLifecycleCallback(@Nullable final VpnServiceLifecycleCallback cb) {
        vpnServiceLifecycleCallback = cb;
    }

    @Nullable private static native String wgGetConfig(int handle);

    private static native int wgGetSocketV4(int handle);

    private static native int wgGetSocketV6(int handle);

    private static native void wgTurnOff(int handle);

    private static native int wgTurnOn(String ifName, int tunFd, String settings);

    private static native String wgVersion();

    /**
     * Method to get the names of running tunnels.
     *
     * @return A set of string values denoting names of running tunnels.
     */
    @Override
    public synchronized Set<String> getRunningTunnelNames() {
        if (currentTunnel != null) {
            final Set<String> runningTunnels = new ArraySet<>();
            runningTunnels.add(currentTunnel.getName());
            return runningTunnels;
        }
        return Collections.emptySet();
    }

    /**
     * Get the associated {@link State} for a given {@link Tunnel}.
     *
     * @param tunnel The tunnel to examine the state of.
     * @return {@link State} associated with the given tunnel.
     */
    @Override
    public synchronized State getState(final Tunnel tunnel) {
        return currentTunnel == tunnel ? State.UP : State.DOWN;
    }

    /**
     * Get the associated {@link Statistics} for a given {@link Tunnel}.
     *
     * @param tunnel The tunnel to retrieve statistics for.
     * @return {@link Statistics} associated with the given tunnel.
     */
    @Override
    public synchronized Statistics getStatistics(final Tunnel tunnel) {
        final Statistics stats = new Statistics();
        if (tunnel != currentTunnel || currentTunnelHandle == -1)
            return stats;
        final String config = wgGetConfig(currentTunnelHandle);
        if (config == null)
            return stats;
        Key key = null;
        long rx = 0;
        long tx = 0;
        long latestHandshakeMSec = 0;
        for (final String line : config.split("\\n")) {
            if (line.startsWith("public_key=")) {
                if (key != null)
                    stats.add(key, rx, tx, latestHandshakeMSec);
                rx = 0;
                tx = 0;
                latestHandshakeMSec = 0;
                try {
                    key = Key.fromHex(line.substring(11));
                } catch (final KeyFormatException ignored) {
                    key = null;
                }
            } else if (line.startsWith("rx_bytes=")) {
                if (key == null)
                    continue;
                try {
                    rx = Long.parseLong(line.substring(9));
                } catch (final NumberFormatException ignored) {
                    rx = 0;
                }
            } else if (line.startsWith("tx_bytes=")) {
                if (key == null)
                    continue;
                try {
                    tx = Long.parseLong(line.substring(9));
                } catch (final NumberFormatException ignored) {
                    tx = 0;
                }
            } else if (line.startsWith("last_handshake_time_sec=")) {
                if (key == null)
                    continue;
                try {
                    latestHandshakeMSec += Long.parseLong(line.substring(24)) * 1000;
                } catch (final NumberFormatException ignored) {
                    latestHandshakeMSec = 0;
                }
            } else if (line.startsWith("last_handshake_time_nsec=")) {
                if (key == null)
                    continue;
                try {
                    latestHandshakeMSec += Long.parseLong(line.substring(25)) / 1000000;
                } catch (final NumberFormatException ignored) {
                    latestHandshakeMSec = 0;
                }
            }
        }
        if (key != null)
            stats.add(key, rx, tx, latestHandshakeMSec);
        return stats;
    }

    /**
     * Get the version of the underlying wireguard-go library.
     *
     * @return {@link String} value of the version of the wireguard-go library.
     */
    @Override
    public String getVersion() {
        return wgVersion();
    }

    /**
     * Determines if the service is running in always-on VPN mode.
     * @return {@link boolean} whether the service is running in always-on VPN mode.
     */
    @Override
    public boolean isAlwaysOn() throws ExecutionException, InterruptedException, TimeoutException {
        return vpnService.get(0, TimeUnit.NANOSECONDS).isAlwaysOn();
    }

    /**
     * Determines if the service is running in always-on VPN lockdown mode.
     * @return {@link boolean} whether the service is running in always-on VPN lockdown mode.
     */
    @Override
    public boolean isLockdownEnabled() throws ExecutionException, InterruptedException, TimeoutException {
        return vpnService.get(0, TimeUnit.NANOSECONDS).isLockdownEnabled();
    }

    /**
     * Starts and registers {@link VpnService} without creating the WireGuard tunnel yet.
     *
     * TURN uses this service for {@link android.net.VpnService#protect(int)} before the
     * userspace tunnel is brought up.
     */
    public VpnService ensureVpnServiceReady() throws Exception {
        if (VpnService.prepare(context) != null)
            throw new BackendException(Reason.VPN_NOT_AUTHORIZED);

        final CompletableFuture<VpnService> serviceFuture;
        synchronized (VPN_SERVICE_LOCK) {
            if (activeVpnService != null && !activeVpnService.isShuttingDown()) {
                serviceFuture = CompletableFuture.completedFuture(activeVpnService);
            } else {
                serviceFuture = vpnService;
                Log.d(TAG, "Requesting to start foreground VpnService");
                final Intent serviceIntent = new Intent(context, VpnService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(serviceIntent);
                else
                    context.startService(serviceIntent);
            }
        }

        final VpnService service;
        try {
            service = serviceFuture.get(3, TimeUnit.SECONDS);
        } catch (final TimeoutException e) {
            final Exception be = new BackendException(Reason.UNABLE_TO_START_VPN);
            be.initCause(e);
            throw be;
        }
        if (service.isShuttingDown())
            throw new BackendException(Reason.UNABLE_TO_START_VPN);
        service.setOwner(this);
        return service;
    }

    /**
     * Stops {@link VpnService} when it was only prepared for TURN startup and no tunnel was
     * successfully activated.
     */
    public synchronized void stopVpnServiceIfIdle() {
        if (currentTunnelHandle != -1)
            return;
        final VpnService service;
        synchronized (VPN_SERVICE_LOCK) {
            service = activeVpnService;
        }
        if (service != null)
            service.requestStopIfIdle();
    }

    /**
     * Change the state of a given {@link Tunnel}, optionally applying a given {@link Config}.
     *
     * @param tunnel The tunnel to control the state of.
     * @param state  The new state for this tunnel. Must be {@code UP}, {@code DOWN}, or
     *               {@code TOGGLE}.
     * @param config The configuration for this tunnel, may be null if state is {@code DOWN}.
     * @return {@link State} of the tunnel after state changes are applied.
     * @throws Exception Exception raised while changing tunnel state.
     */
    @Override
    public synchronized State setState(final Tunnel tunnel, State state, @Nullable final Config config) throws Exception {
        final State originalState = getState(tunnel);

        if (state == State.TOGGLE)
            state = originalState == State.UP ? State.DOWN : State.UP;
        if (state == originalState && tunnel == currentTunnel && config == currentConfig)
            return originalState;
        if (state == State.UP) {
            final Config originalConfig = currentConfig;
            final Tunnel originalTunnel = currentTunnel;
            if (currentTunnel != null)
                setStateInternal(currentTunnel, null, State.DOWN);
            try {
                setStateInternal(tunnel, config, state);
            } catch (final Exception e) {
                if (originalTunnel != null)
                    setStateInternal(originalTunnel, originalConfig, State.UP);
                throw e;
            }
        } else if (state == State.DOWN && tunnel == currentTunnel) {
            setStateInternal(tunnel, null, State.DOWN);
        }
        return getState(tunnel);
    }

    private void setStateInternal(final Tunnel tunnel, @Nullable final Config config, final State state)
            throws Exception {
        Log.i(TAG, "Bringing tunnel " + tunnel.getName() + ' ' + state);

        if (state == State.UP) {
            if (config == null)
                throw new BackendException(Reason.TUNNEL_MISSING_CONFIG);
            final VpnService service = ensureVpnServiceReady();

            if (currentTunnelHandle != -1) {
                Log.w(TAG, "Tunnel already up");
                return;
            }


            dnsRetry: for (int i = 0; i < DNS_RESOLUTION_RETRIES; ++i) {
                // Pre-resolve IPs so they're cached when building the userspace string
                for (final Peer peer : config.getPeers()) {
                    final InetEndpoint ep = peer.getEndpoint().orElse(null);
                    if (ep == null)
                        continue;
                    if (ep.getResolved().orElse(null) == null) {
                        if (i < DNS_RESOLUTION_RETRIES - 1) {
                            Log.w(TAG, "DNS host \"" + ep.getHost() + "\" failed to resolve; trying again");
                            Thread.sleep(1000);
                            continue dnsRetry;
                        } else
                            throw new BackendException(Reason.DNS_RESOLUTION_FAILURE, ep.getHost());
                    }
                }
                break;
            }

            // Build config
            final String goConfig = config.toWgUserspaceString();

            // Create the vpn tunnel with android API
            final VpnService.Builder builder = service.getBuilder();
            builder.setSession(tunnel.getName());

            final Set<String> includedApplications = config.getInterface().getIncludedApplications();
            if (includedApplications.isEmpty()) {
                final Set<String> disallowedApplications = new LinkedHashSet<>(
                        config.getInterface().getExcludedApplications());
                disallowedApplications.addAll(globallyExcludedApplications);
                for (final String excludedApplication : disallowedApplications) {
                    try {
                        builder.addDisallowedApplication(excludedApplication);
                    } catch (final android.content.pm.PackageManager.NameNotFoundException e) {
                        Log.w(TAG, "Ignoring unavailable excluded application " + excludedApplication);
                    }
                }
            } else {
                boolean addedAllowedApplication = false;
                boolean applicationPackageAdded = false;
                for (final String includedApplication : includedApplications) {
                    if (!globallyExcludedApplications.contains(includedApplication)) {
                        builder.addAllowedApplication(includedApplication);
                        addedAllowedApplication = true;
                        applicationPackageAdded |= context.getPackageName().equals(includedApplication);
                    }
                }
                // The no-root tether proxy runs in this package. Its accepted downstream
                // connections must enter the VPN even when a profile uses an app allow-list.
                if (!applicationPackageAdded) {
                    builder.addAllowedApplication(context.getPackageName());
                    addedAllowedApplication = true;
                }
                // An empty allow-list means "all applications" to VpnService.Builder. Keep the
                // tunnel restricted if the global exclusions removed every profile-specific app.
                if (!addedAllowedApplication)
                    builder.addAllowedApplication(context.getPackageName());
            }

            for (final InetNetwork addr : config.getInterface().getAddresses())
                builder.addAddress(addr.getAddress(), addr.getMask());

            for (final InetAddress addr : config.getInterface().getDnsServers())
                builder.addDnsServer(addr.getHostAddress());

            for (final String dnsSearchDomain : config.getInterface().getDnsSearchDomains())
                builder.addSearchDomain(dnsSearchDomain);

            boolean sawDefaultRoute = false;
            final List<InetNetwork> allowedRoutes = new ArrayList<>();
            for (final Peer peer : config.getPeers()) {
                for (final InetNetwork addr : peer.getAllowedIps()) {
                    if (addr.getMask() == 0)
                        sawDefaultRoute = true;
                    allowedRoutes.add(addr);
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                for (final InetNetwork addr : allowedRoutes)
                    builder.addRoute(addr.getAddress(), addr.getMask());
                final List<InetNetwork> relevantExcludedRoutes = RouteExcluder.intersect(allowedRoutes, excludedRoutes);
                final List<InetNetwork> supportedExcludedRoutes = new ArrayList<>(relevantExcludedRoutes.size());
                int skippedRoutes = 0;
                for (final InetNetwork addr : relevantExcludedRoutes) {
                    if (!RouteExcluder.isVpnServiceRouteSupported(addr)) {
                        ++skippedRoutes;
                        Log.w(TAG, "Skipping direct route rejected by Android VpnService: " + addr);
                        continue;
                    }
                    supportedExcludedRoutes.add(addr);
                }
                final List<InetNetwork> compactedExcludedRoutes = RouteExcluder.compactForVpnService(
                        supportedExcludedRoutes, MAX_VPN_EXCLUDED_ROUTES);
                if (compactedExcludedRoutes.size() < supportedExcludedRoutes.size())
                    Log.w(TAG, "Compacted " + supportedExcludedRoutes.size() + " direct routes to " +
                            compactedExcludedRoutes.size() + " to stay within Android Binder limits");
                Log.i(TAG, "Applying " + compactedExcludedRoutes.size() + " direct destination routes");
                for (final InetNetwork addr : compactedExcludedRoutes)
                    builder.excludeRoute(new IpPrefix(addr.getAddress(), addr.getMask()));
                if (skippedRoutes > 0)
                    Log.w(TAG, "Skipped " + skippedRoutes + " unsupported local direct routes");
            } else {
                final List<InetNetwork> includedRoutes = RouteExcluder.exclude(allowedRoutes, excludedRoutes);
                Log.i(TAG, "Applying " + includedRoutes.size() + " VPN destination routes");
                for (final InetNetwork addr : includedRoutes)
                    builder.addRoute(addr.getAddress(), addr.getMask());
            }

            // DNS must not inherit a broader excluded route. Re-adding these host routes after
            // exclusions gives the resolver a deterministic path through the VPN and prevents a
            // direct-DNS fallback when split routing is enabled.
            if (sawDefaultRoute) {
                for (final InetAddress dns : config.getInterface().getDnsServers())
                    builder.addRoute(dns, dns.getAddress().length == 4 ? 32 : 128);
            }

            // "Kill-switch" semantics
            if (!(sawDefaultRoute && config.getPeers().size() == 1)) {
                builder.allowFamily(OsConstants.AF_INET);
                builder.allowFamily(OsConstants.AF_INET6);
            }

            builder.setMtu(config.getInterface().getMtu().orElse(1280));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                builder.setMetered(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                service.setUnderlyingNetworks(null);

            builder.setBlocking(true);
            try (final ParcelFileDescriptor tun = builder.establish()) {
                if (tun == null)
                    throw new BackendException(Reason.TUN_CREATION_ERROR);
                Log.d(TAG, "Go backend " + wgVersion());
                currentTunnelHandle = wgTurnOn(tunnel.getName(), tun.detachFd(), goConfig);
            }
            if (currentTunnelHandle < 0)
                throw new BackendException(Reason.GO_ACTIVATION_ERROR_CODE, currentTunnelHandle);

            currentTunnel = tunnel;
            currentConfig = config;

            // Protect WireGuard sockets
            service.protect(wgGetSocketV4(currentTunnelHandle));
            service.protect(wgGetSocketV6(currentTunnelHandle));
        } else {
            if (currentTunnelHandle == -1) {
                Log.w(TAG, "Tunnel already down");
                return;
            }
            int handleToClose = currentTunnelHandle;
            currentTunnel = null;
            currentTunnelHandle = -1;
            currentConfig = null;
            wgTurnOff(handleToClose);
            final VpnService service = activeVpnService;
            if (service != null)
                service.requestStopIfIdle();
        }

        tunnel.onStateChange(state);
    }

    private synchronized void handleVpnServiceTermination() {
        final Tunnel tunnel = currentTunnel;
        if (currentTunnelHandle != -1)
            wgTurnOff(currentTunnelHandle);
        currentTunnel = null;
        currentTunnelHandle = -1;
        currentConfig = null;
        if (tunnel != null)
            tunnel.onStateChange(State.DOWN);
    }

    private static void registerVpnService(final VpnService service) {
        synchronized (VPN_SERVICE_LOCK) {
            activeVpnService = service;
            if (vpnService.isDone())
                vpnService = new CompletableFuture<>();
            vpnService.complete(service);
        }
    }

    private static void unregisterVpnService(final VpnService service) {
        synchronized (VPN_SERVICE_LOCK) {
            if (activeVpnService != service)
                return;
            activeVpnService = null;
            vpnService = new CompletableFuture<>();
        }
    }

    private static boolean isActiveVpnService(final VpnService service) {
        synchronized (VPN_SERVICE_LOCK) {
            return activeVpnService == service;
        }
    }

    /**
     * Callback for {@link GoBackend} that is invoked when {@link VpnService} is started by the
     * system's Always-On VPN mode.
     */
    public interface AlwaysOnCallback {
        void alwaysOnTriggered();
    }

    public interface VpnServiceLifecycleCallback {
        void onUnexpectedTermination(String reason);
    }

    /**
     * {@link android.net.VpnService} implementation for {@link GoBackend}
     */
    public static class VpnService extends android.net.VpnService {
        private static final String NOTIFICATION_CHANNEL_ID = "rabbithole_tunnel";
        private static final int NOTIFICATION_ID = 43021;
        private static final long IDLE_STOP_DELAY_MS = 1_200L;
        @Nullable private GoBackend owner;
        private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final Runnable idleStop = this::stopIfStillIdle;

        public Builder getBuilder() {
            return new Builder();
        }

        @Override
        public void onCreate() {
            super.onCreate();
            Log.d(TAG, "VpnService.onCreate() called");
            startAsForeground();
            // CORRECT ORDER: First register in TurnBackend (JNI), then complete Future
            // This ensures JNI is ready before TurnProxyManager gets the Future
            Log.d(TAG, "Calling TurnBackend.onVpnServiceCreated()...");
            TurnBackend.onVpnServiceCreated(this);
            Log.d(TAG, "TurnBackend.onVpnServiceCreated() complete");
            registerVpnService(this);
        }

        @Override
        public void onDestroy() {
            mainHandler.removeCallbacks(idleStop);
            shutdownOwner("destroyed");
            TurnBackend.onVpnServiceDestroyed(this);
            unregisterVpnService(this);
            stopForeground(STOP_FOREGROUND_REMOVE);
            super.onDestroy();
        }

        @Override
        public void onRevoke() {
            Log.w(TAG, "VpnService permission revoked by the system");
            shutdownOwner("revoked");
            stopSelf();
            super.onRevoke();
        }

        @Override
        public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {
            startAsForeground();
            if (intent == null || intent.getComponent() == null || !intent.getComponent().getPackageName().equals(getPackageName())) {
                Log.d(TAG, "Service started by Always-on VPN feature");
                if (alwaysOnCallback != null)
                    alwaysOnCallback.alwaysOnTriggered();
            }
            return START_STICKY;
        }

        public void setOwner(final GoBackend owner) {
            if (shuttingDown.get())
                throw new IllegalStateException("VpnService is shutting down");
            mainHandler.removeCallbacks(idleStop);
            this.owner = owner;
        }

        public boolean isShuttingDown() {
            return shuttingDown.get();
        }

        public void requestStopIfIdle() {
            final GoBackend currentOwner = owner;
            if (currentOwner != null) {
                synchronized (currentOwner) {
                    if (currentOwner.currentTunnelHandle != -1)
                        return;
                }
            }
            mainHandler.removeCallbacks(idleStop);
            mainHandler.postDelayed(idleStop, IDLE_STOP_DELAY_MS);
        }

        private void stopIfStillIdle() {
            final GoBackend currentOwner = owner;
            if (currentOwner != null) {
                synchronized (currentOwner) {
                    if (currentOwner.currentTunnelHandle != -1)
                        return;
                    shutdownOwner("idle");
                }
            } else {
                shutdownOwner("idle");
            }
            stopSelf();
        }

        private void shutdownOwner(final String reason) {
            if (!shuttingDown.compareAndSet(false, true))
                return;
            mainHandler.removeCallbacks(idleStop);
            Log.d(TAG, "Stopping VpnService lifecycle: " + reason);
            final boolean controlsActiveBackend = isActiveVpnService(this);
            if (controlsActiveBackend)
                TurnBackend.wgTurnProxyStop();
            final GoBackend currentOwner = owner;
            owner = null;
            if (currentOwner != null && controlsActiveBackend)
                currentOwner.handleVpnServiceTermination();
            if (!"idle".equals(reason) && controlsActiveBackend && vpnServiceLifecycleCallback != null)
                vpnServiceLifecycleCallback.onUnexpectedTermination(reason);
        }

        private void startAsForeground() {
            final NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                final NotificationChannel channel = new NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        getString(com.wireguard.android.tunnel.R.string.vpn_notification_channel),
                        NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                notificationManager.createNotificationChannel(channel);
            }

            final Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                    : new Notification.Builder(this);
            builder.setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setContentTitle(getApplicationInfo().loadLabel(getPackageManager()))
                    .setContentText(getString(com.wireguard.android.tunnel.R.string.vpn_notification_text))
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE);
            final Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launchIntent != null) {
                final PendingIntent contentIntent = PendingIntent.getActivity(
                        this,
                        0,
                        launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                builder.setContentIntent(contentIntent);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                        NOTIFICATION_ID,
                        builder.build(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
            } else {
                startForeground(NOTIFICATION_ID, builder.build());
            }
        }
    }
}
