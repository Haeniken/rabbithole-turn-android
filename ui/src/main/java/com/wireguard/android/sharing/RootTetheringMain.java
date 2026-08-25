/* SPDX-License-Identifier: Apache-2.0 */
package com.wireguard.android.sharing;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.TetheringManager;
import android.net.TetheringManager.StartTetheringCallback;
import android.net.TetheringManager.TetheringRequest;
import android.os.Build;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Small root app_process entry point used to call the privileged tethering API. */
@SuppressLint("NewApi") // TetheringManager exists since API 30; SDK 36 reclassified this surface.
public final class RootTetheringMain {
    private RootTetheringMain() { }

    public static void main(final String[] args) throws Exception {
        if (args.length != 2)
            throw new IllegalArgumentException("Usage: start|stop TYPE");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
            throw new UnsupportedOperationException("Root tether control requires Android 11+");
        final int type = Integer.parseInt(args[1]);
        final Context context = systemContext();
        final TetheringManager manager = context.getSystemService(TetheringManager.class);
        if (manager == null)
            throw new IllegalStateException("TetheringManager is unavailable");
        if ("stop".equals(args[0])) {
            stopTethering(manager, type);
            return;
        }
        if (!"start".equals(args[0]))
            throw new IllegalArgumentException("Unknown action: " + args[0]);

        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicInteger error = new AtomicInteger(0);
        final TetheringRequest request = new TetheringRequest.Builder(type).build();
        manager.startTethering(request, Runnable::run, new StartTetheringCallback() {
            @Override
            public void onTetheringStarted() {
                completed.countDown();
            }

            @Override
            public void onTetheringFailed(final int failure) {
                error.set(failure);
                completed.countDown();
            }
        });
        if (!completed.await(20, TimeUnit.SECONDS))
            throw new IllegalStateException("Timed out starting tethering");
        if (error.get() != 0)
            throw new IllegalStateException("Tethering failed: " + error.get());
    }

    private static void stopTethering(final TetheringManager manager, final int type) throws Exception {
        try {
            // Android 11-15 API. Reflection keeps the app buildable with the Android 16 SDK,
            // where this overload was replaced by an asynchronous request API.
            final Method legacy = TetheringManager.class.getMethod("stopTethering", int.class);
            legacy.invoke(manager, type);
            return;
        } catch (final NoSuchMethodException ignored) {
            // Continue with the Android 16+ API below.
        }

        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicInteger error = new AtomicInteger(0);
        final TetheringRequest request = new TetheringRequest.Builder(type).build();
        final Class<?> callbackClass = Class.forName("android.net.TetheringManager$StopTetheringCallback");
        final Object callback = Proxy.newProxyInstance(
            callbackClass.getClassLoader(),
            new Class<?>[] { callbackClass },
            (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "RabbitHoleStopTetheringCallback";
                        default -> null;
                    };
                }
                if ("onStopTetheringFailed".equals(method.getName())) {
                    error.set((Integer) args[0]);
                    completed.countDown();
                } else if ("onStopTetheringSucceeded".equals(method.getName())) {
                    completed.countDown();
                }
                return null;
            }
        );
        final Method modern = TetheringManager.class.getMethod(
            "stopTethering",
            TetheringRequest.class,
            Executor.class,
            callbackClass
        );
        modern.invoke(manager, request, (Executor) Runnable::run, callback);
        if (!completed.await(20, TimeUnit.SECONDS))
            throw new IllegalStateException("Timed out stopping tethering");
        if (error.get() != 0)
            throw new IllegalStateException("Stopping tethering failed: " + error.get());
    }

    private static Context systemContext() throws Exception {
        final Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        final Method systemMain = activityThreadClass.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        final Object activityThread = systemMain.invoke(null);
        final Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return (Context) getSystemContext.invoke(activityThread);
    }
}
