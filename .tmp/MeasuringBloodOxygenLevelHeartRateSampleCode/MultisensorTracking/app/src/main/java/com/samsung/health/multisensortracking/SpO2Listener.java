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

import android.util.Log;

import androidx.annotation.NonNull;

import com.samsung.android.service.health.tracking.HealthTracker;
import com.samsung.android.service.health.tracking.data.DataPoint;
import com.samsung.android.service.health.tracking.data.ValueKey;

import java.util.List;

public class SpO2Listener extends BaseListener {
    private final static String APP_TAG = "SpO2Listener";

    SpO2Listener() {
        final HealthTracker.TrackerEventListener trackerEventListener = new HealthTracker.TrackerEventListener() {
            @Override
            public void onDataReceived(@NonNull List<DataPoint> list) {
                for (DataPoint data : list) {
                    updateSpo2(data);
                }
            }

            @Override
            public void onFlushCompleted() {
                Log.i(APP_TAG, " onFlushCompleted called");
            }

            @Override
            public void onError(HealthTracker.TrackerError trackerError) {
                Log.e(APP_TAG, " onError called: " + trackerError);
                setHandlerRunning(false);
                if (trackerError == HealthTracker.TrackerError.PERMISSION_ERROR) {
                    TrackerDataNotifier.getInstance().notifyError(R.string.NoPermission);
                }
                if (trackerError == HealthTracker.TrackerError.SDK_POLICY_ERROR) {
                    TrackerDataNotifier.getInstance().notifyError(R.string.SdkPolicyError);
                }
            }
        };
        setTrackerEventListener(trackerEventListener);
    }

    /*******************************************************************************************
     * [Practice 5] Read values from DataPoint object
     *  - Get blood oxygen level status
     *  - Get blood oxygen level value
     -------------------------------------------------------------------------------------------
     *  - (Hint) Replace TODO 5 with parts of code
     *      (1) remove SpO2Status.CALCULATING and
     *          set status from 'dataPoint' object using dataPoint.getValue(ValueKey.SpO2Set.STATUS)
     *      (2) set spo2Value from 'dataPoint' object using dataPoint.getValue(ValueKey.SpO2Set.SPO2)
     *          if status is 'SpO2Status.MEASUREMENT_COMPLETED'
     ******************************************************************************************/

    public void updateSpo2(DataPoint dataPoint) {

        int status = SpO2Status.CALCULATING; //"TODO 5 (1)"
        int spo2Value = 0;

        //"TODO 5 (2)"

        TrackerDataNotifier.getInstance().notifySpO2TrackerObservers(status, spo2Value);
        Log.d(APP_TAG, dataPoint.toString());
    }

}
