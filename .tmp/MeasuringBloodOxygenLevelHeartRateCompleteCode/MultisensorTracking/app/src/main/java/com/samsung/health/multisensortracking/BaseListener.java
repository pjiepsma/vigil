/*
 * Copyright 2022 Samsung Electronics Co., Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.samsung.health.multisensortracking;

import android.os.Handler;
import android.util.Log;

import com.samsung.android.service.health.tracking.HealthTracker;

public class BaseListener {

    private final static String APP_TAG = "BaseListener";

    private Handler handler;
    private HealthTracker healthTracker;
    private boolean isHandlerRunning = false;

    private HealthTracker.TrackerEventListener trackerEventListener = null;

    public void setHealthTracker(HealthTracker tracker) {
        healthTracker = tracker;
    }

    public void setHandler(Handler handler) {
        this.handler = handler;
    }

    public void setHandlerRunning(boolean handlerRunning) {
        isHandlerRunning = handlerRunning;
    }

    public void setTrackerEventListener(HealthTracker.TrackerEventListener tracker) {
        trackerEventListener = tracker;
    }

    public void startTracker() {
        Log.i(APP_TAG, "startTracker called ");
        Log.d(APP_TAG, "healthTracker: " + healthTracker.toString());
        Log.d(APP_TAG, "trackerEventListener: " + trackerEventListener.toString());
        if (!isHandlerRunning) {
            handler.post(() -> {
                healthTracker.setEventListener(trackerEventListener);
                setHandlerRunning(true);
            });
        }
    }

    public void stopTracker() {
        Log.i(APP_TAG, "stopTracker called ");
        Log.d(APP_TAG, "healthTracker: " + healthTracker.toString());
        Log.d(APP_TAG, "trackerEventListener: " + trackerEventListener.toString());
        if (isHandlerRunning) {
            healthTracker.unsetEventListener();
            setHandlerRunning(false);

            handler.removeCallbacksAndMessages(null);
        }
    }

}
