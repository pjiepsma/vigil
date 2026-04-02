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

import java.util.ArrayList;
import java.util.List;

public class TrackerDataNotifier {
    private static TrackerDataNotifier instance;

    private final List<TrackerDataObserver> observers = new ArrayList<>();

    public static TrackerDataNotifier getInstance() {
        if (instance == null) {
            instance = new TrackerDataNotifier();
        }
        return instance;
    }

    public void addObserver(TrackerDataObserver observer) {
        observers.add(observer);
    }

    public void removeObserver(TrackerDataObserver observer) {
        observers.remove(observer);
    }

    public void notifyHeartRateTrackerObservers(HeartRateData hrData) {
        observers.forEach(observer -> observer.onHeartRateTrackerDataChanged(hrData));
    }

    public void notifySpO2TrackerObservers(int status, int spO2Value) {
        observers.forEach(observer -> observer.onSpO2TrackerDataChanged(status, spO2Value));
    }

    public void notifyError(int errorResourceId) {
        observers.forEach(observer -> observer.onError(errorResourceId));
    }
}
