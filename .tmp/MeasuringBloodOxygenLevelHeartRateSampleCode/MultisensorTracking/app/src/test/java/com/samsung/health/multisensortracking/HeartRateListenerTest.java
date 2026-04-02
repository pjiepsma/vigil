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


import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.samsung.android.service.health.tracking.data.DataPoint;
import com.samsung.android.service.health.tracking.data.Value;
import com.samsung.android.service.health.tracking.data.ValueKey;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(MockitoJUnitRunner.class)
public class HeartRateListenerTest {

    @Mock
    TrackerDataObserver trackerDataObserver;

    @InjectMocks
    HeartRateListener heartRateListener;

    @Test
    public void shouldReadValuesFromDataPoint_P() {
        //given
        final HeartRateData hrData = new HeartRateData(HeartRateStatus.HR_STATUS_FIND_HR, 100, 189, 0);
        final Map<ValueKey, Value> values = new HashMap<>();

        @SuppressWarnings("rawtypes")

        final List<Integer> ibiList = new ArrayList<>();
        ibiList.add(hrData.getHrIbi());
        final List<Integer> ibiStatusList = new ArrayList<>();
        ibiStatusList.add(hrData.qIbi);

        values.put(ValueKey.HeartRateSet.HEART_RATE_STATUS, new Value<>(hrData.status));
        values.put(ValueKey.HeartRateSet.HEART_RATE, new Value<>(hrData.hr));
        values.put(ValueKey.HeartRateSet.IBI_LIST, new Value<>(ibiList));
        values.put(ValueKey.HeartRateSet.IBI_STATUS_LIST, new Value<>(ibiStatusList));
        final DataPoint dataPoint = new DataPoint(values);

        //when
        doAnswer(invocation -> {
            final HeartRateData arg0 = invocation.getArgument(0);

            assertEquals(hrData.status, arg0.status);
            assertEquals(hrData.hr, arg0.hr);
            assertEquals(hrData.ibi, arg0.ibi);
            assertEquals(hrData.qIbi, arg0.qIbi);
            return null;
        }).when(trackerDataObserver).onHeartRateTrackerDataChanged(any(HeartRateData.class));

        TrackerDataNotifier.getInstance().addObserver(trackerDataObserver);
        heartRateListener.readValuesFromDataPoint(dataPoint);

        //then
        TrackerDataNotifier.getInstance().removeObserver(trackerDataObserver);

    }
}
