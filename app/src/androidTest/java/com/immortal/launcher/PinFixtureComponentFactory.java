/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.content.Intent;

/** The provider initializes the isolated fixture loader before Android creates this Activity. */
public final class PinFixtureComponentFactory extends AppComponentFactory {
    @Override public Activity instantiateActivity(ClassLoader original, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (name.equals("com.immortal.launcher.PinFixtureActivity") ||
                name.equals("com.immortal.launcher.TodayCalendarFixtureActivity") ||
                name.equals("com.immortal.launcher.IssPassFixtureActivity")) {
            return (Activity) PinFixtureBridgeProvider.loader().loadClass(name).newInstance();
        }
        return super.instantiateActivity(original, name, intent);
    }
}
