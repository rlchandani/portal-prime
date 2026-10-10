/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import dalvik.system.PathClassLoader;
import java.io.File;

/**
 * Test-only standalone bootstrap. AGP omits target-shared Kotlin dependencies from the test APK.
 * Load our same-signed installed target + test APK using public APIs, without copying code,
 * private-field reflection, app-data access, or inheriting the target's UID/permissions.
 */
public final class PinFixtureBridgeProvider extends ContentProvider {
    private static volatile ClassLoader fixtureLoader;
    private ContentProvider delegate;

    public static ClassLoader loader() {
        ClassLoader value = fixtureLoader;
        if (value == null) throw new IllegalStateException("Fixture provider not initialized");
        return value;
    }

    @Override public boolean onCreate() {
        Context context = getContext();
        if (context == null) throw new IllegalStateException("Missing fixture context");
        try {
            String target = "com.immortal.launcher.debug";
            if (context.getPackageManager().checkSignatures(target, context.getPackageName())
                    != PackageManager.SIGNATURE_MATCH) throw new SecurityException("Fixture signature mismatch");
            ApplicationInfo installed = context.getPackageManager().getApplicationInfo(target, 0);
            String paths = installed.sourceDir + File.pathSeparator + context.getApplicationInfo().sourceDir;
            // A parent with the test APK would resolve its Kotlin classes without their shared
            // dependencies. Both APKs must instead be resolved in this one child loader.
            fixtureLoader = new PathClassLoader(paths, getClass().getClassLoader().getParent());
            delegate = (ContentProvider) loader().loadClass("com.immortal.launcher.PinFixtureProvider").newInstance();
            ProviderInfo info = new ProviderInfo();
            info.authority = "com.immortal.launcher.debug.test.pin-fixture";
            info.exported = true;
            delegate.attachInfo(context, info);
            return true;
        } catch (ReflectiveOperationException | PackageManager.NameNotFoundException failure) {
            throw new IllegalStateException("Standalone fixture dependency load failed", failure);
        }
    }

    @Override public Bundle call(String method, String argument, Bundle extras) {
        // Preserve the original Binder caller: the Kotlin delegate still requires the signed
        // debug target and its exact per-run nonce for every state/cleanup command.
        return delegate.call(method, argument, extras);
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
