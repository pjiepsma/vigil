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

public class HeartRateStatus {
    public static final int HR_STATUS_NONE = 0;
    public static final int HR_STATUS_FIND_HR = 1;
    public static final int HR_STATUS_ATTACHED = -1;
    public static final int HR_STATUS_DETECT_MOVE = -2;
    public static final int HR_STATUS_DETACHED = -3;
    public static final int HR_STATUS_LOW_RELIABILITY = -8;
    public static final int HR_STATUS_VERY_LOW_RELIABILITY = -10;
    public static final int HR_STATUS_NO_DATA_FLUSH = -99;
}
