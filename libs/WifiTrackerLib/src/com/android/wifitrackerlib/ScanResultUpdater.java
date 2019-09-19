/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wifitrackerlib;

import android.net.wifi.ScanResult;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Utility class to keep a running list of scan results merged by BSSID.
 *
 * Thread-safe.
 */
public class ScanResultUpdater {
    private HashMap<String, ScanResult> mScanResultsByBssid = new HashMap<>();
    private long mMaxScanAgeMillis;
    private Object mLock = new Object();

    /**
     * Creates a ScanResultUpdater with no max scan age.
     */
    public ScanResultUpdater() {
        this(Long.MAX_VALUE);
    }

    /**
     * Creates a ScanResultUpdater with a max scan age in milliseconds. Scans older than this limit
     * will be pruned upon update/retrieval to keep the size of the scan list down.
     */
    public ScanResultUpdater(long maxScanAgeMillis) {
        mMaxScanAgeMillis = maxScanAgeMillis;
    }

    /**
     * Updates scan result list and replaces older scans of the same BSSID.
     */
    public void update(List<ScanResult> newResults) {
        synchronized (mLock) {
            evictOldScans();

            for (ScanResult result : newResults) {
                ScanResult prevResult = mScanResultsByBssid.get(result.BSSID);
                if (prevResult == null || (prevResult.timestamp < result.timestamp)) {
                    mScanResultsByBssid.put(result.BSSID, result);
                }
            }
        }
    }

    /**
     * Returns all seen scan results merged by BSSID.
     */
    public List<ScanResult> getScanResults() {
        return getScanResults(mMaxScanAgeMillis);
    }

    /**
     * Returns all seen scan results merged by BSSID and newer than maxScanAgeMillis.
     * maxScanAgeMillis must be less than or equal to the mMaxScanAgeMillis field if it was set.
     */
    public List<ScanResult> getScanResults(long maxScanAgeMillis) throws IllegalArgumentException {
        if (maxScanAgeMillis > mMaxScanAgeMillis) {
            throw new IllegalArgumentException(
                    "maxScanAgeMillis argument cannot be greater than mMaxScanAgeMillis!");
        }
        synchronized (mLock) {
            List<ScanResult> ageFilteredResults = new ArrayList<>();
            for (ScanResult result : mScanResultsByBssid.values()) {
                if (SystemClock.elapsedRealtime() - result.timestamp <= maxScanAgeMillis) {
                    ageFilteredResults.add(result);
                }
            }
            return ageFilteredResults;
        }
    }

    private void evictOldScans() {
        synchronized (mLock) {
            mScanResultsByBssid.entrySet().removeIf((entry) ->
                    SystemClock.elapsedRealtime() - entry.getValue().timestamp > mMaxScanAgeMillis);
        }
    }
}
