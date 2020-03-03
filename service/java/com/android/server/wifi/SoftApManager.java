/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi;

import static com.android.server.wifi.util.ApConfigUtil.ERROR_GENERIC;
import static com.android.server.wifi.util.ApConfigUtil.ERROR_NO_CHANNEL;
import static com.android.server.wifi.util.ApConfigUtil.ERROR_UNSUPPORTED_CONFIGURATION;
import static com.android.server.wifi.util.ApConfigUtil.SUCCESS;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.SoftApCapability;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.SoftApInfo;
import android.net.wifi.WifiAnnotations;
import android.net.wifi.WifiClient;
import android.net.wifi.WifiManager;
import android.net.wifi.nl80211.NativeWifiClient;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IState;
import com.android.internal.util.Preconditions;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.internal.util.WakeupMessage;
import com.android.server.wifi.WifiNative.InterfaceCallback;
import com.android.server.wifi.WifiNative.SoftApListener;
import com.android.server.wifi.util.ApConfigUtil;
import com.android.wifi.resources.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Manage WiFi in AP mode.
 * The internal state machine runs under the ClientModeImpl handler thread context.
 */
public class SoftApManager implements ActiveModeManager {
    private static final String TAG = "SoftApManager";

    @VisibleForTesting
    public static final String SOFT_AP_SEND_MESSAGE_TIMEOUT_TAG = TAG
            + " Soft AP Send Message Timeout";

    private final Context mContext;
    private final FrameworkFacade mFrameworkFacade;
    private final WifiNative mWifiNative;

    @VisibleForTesting
    SoftApNotifier mSoftApNotifier;

    private final String mCountryCode;

    private final SoftApStateMachine mStateMachine;

    private final Listener mModeListener;
    private final WifiManager.SoftApCallback mSoftApCallback;

    private String mApInterfaceName;
    private String mDataInterfaceName;
    private boolean mIfaceIsUp;
    private boolean mIfaceIsDestroyed;

    private final WifiApConfigStore mWifiApConfigStore;

    private final WifiMetrics mWifiMetrics;

    private boolean mIsRandomizeBssid;

    @NonNull
    private SoftApModeConfiguration mApConfig;

    @NonNull
    private SoftApInfo mCurrentSoftApInfo = new SoftApInfo();

    @NonNull
    private SoftApCapability mCurrentSoftApCapability;

    private List<WifiClient> mConnectedClients = new ArrayList<>();
    private int mQCNumAssociatedStations = 0;
    private boolean mTimeoutEnabled = false;
    private String[] mdualApInterfaces;
    private boolean mDualSapIfacesDestroyed = false;
    private String mSoftApStartFailureDesc;

    private final SarManager mSarManager;

    private String mStartTimestamp;

    private static final SimpleDateFormat FORMATTER = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

    private BaseWifiDiagnostics mWifiDiagnostics;

    private @Role int mRole = ROLE_UNSPECIFIED;

    @NonNull
    private Set<MacAddress> mBlockedClientList = new HashSet<>();

    @NonNull
    private Set<MacAddress> mAllowedClientList = new HashSet<>();

    /**
     * Listener for soft AP events.
     */
    private final SoftApListener mSoftApListener = new SoftApListener() {

        @Override
        public void onFailure() {
            mStateMachine.sendMessage(SoftApStateMachine.CMD_FAILURE);
        }

        @Override
        public void onConnectedClientsChanged(NativeWifiClient client, boolean isConnected) {
            if (client != null) {
                mStateMachine.sendMessage(SoftApStateMachine.CMD_ASSOCIATED_STATIONS_CHANGED,
                        isConnected ? 1 : 0, 0, client);
            } else {
                Log.e(TAG, "onConnectedClientsChanged: Invalid type returned");
            }
        }

        @Override
        public void onSoftApChannelSwitched(int frequency,
                @WifiAnnotations.Bandwidth int bandwidth) {
            mStateMachine.sendMessage(
                    SoftApStateMachine.CMD_SOFT_AP_CHANNEL_SWITCHED, frequency, bandwidth);
        }

        @Override
        public void onStaConnected(String Macaddr) {
            mStateMachine.sendMessage(
                    SoftApStateMachine.CMD_CONNECTED_STATIONS, Macaddr);
        }

        @Override
        public void onStaDisconnected(String Macaddr) {
            mStateMachine.sendMessage(
                    SoftApStateMachine.CMD_DISCONNECTED_STATIONS, Macaddr);
        }
    };

    public SoftApManager(@NonNull Context context,
                         @NonNull Looper looper,
                         @NonNull FrameworkFacade framework,
                         @NonNull WifiNative wifiNative,
                         String countryCode,
                         @NonNull Listener listener,
                         @NonNull WifiManager.SoftApCallback callback,
                         @NonNull WifiApConfigStore wifiApConfigStore,
                         @NonNull SoftApModeConfiguration apConfig,
                         @NonNull WifiMetrics wifiMetrics,
                         @NonNull SarManager sarManager,
                         @NonNull BaseWifiDiagnostics wifiDiagnostics) {
        mContext = context;
        mFrameworkFacade = framework;
        mSoftApNotifier = new SoftApNotifier(mContext, mFrameworkFacade);
        mWifiNative = wifiNative;
        mCountryCode = countryCode;
        mModeListener = listener;
        mSoftApCallback = callback;
        mWifiApConfigStore = wifiApConfigStore;
        SoftApConfiguration softApConfig = apConfig.getSoftApConfiguration();
        mCurrentSoftApCapability = apConfig.getCapability();
        // null is a valid input and means we use the user-configured tethering settings.
        if (softApConfig == null) {
            softApConfig = mWifiApConfigStore.getApConfiguration();
            // may still be null if we fail to load the default config
        }
        if (softApConfig != null) {
            mIsRandomizeBssid = softApConfig.getBssid() == null;
            softApConfig = mWifiApConfigStore.randomizeBssidIfUnset(mContext, softApConfig);
        }
        mApConfig = new SoftApModeConfiguration(apConfig.getTargetMode(),
                softApConfig, mCurrentSoftApCapability);
        mWifiMetrics = wifiMetrics;
        mSarManager = sarManager;
        mdualApInterfaces = new String[2];
        mWifiDiagnostics = wifiDiagnostics;
        mStateMachine = new SoftApStateMachine(looper);
        if (softApConfig != null) {
            mBlockedClientList = new HashSet<>(softApConfig.getBlockedClientList());
            mAllowedClientList = new HashSet<>(softApConfig.getAllowedClientList());
            mTimeoutEnabled = softApConfig.isAutoShutdownEnabled();
        }
    }

    /**
     * Start soft AP, as configured in the constructor.
     */
    @Override
    public void start() {
        mStateMachine.sendMessage(SoftApStateMachine.CMD_START);
    }

    /**
     * Stop soft AP.
     */
    @Override
    public void stop() {
        Log.d(TAG, " currentstate: " + getCurrentStateName());
        if (mApInterfaceName != null) {
            if (mIfaceIsUp) {
                updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                        WifiManager.WIFI_AP_STATE_ENABLED, 0);
            } else {
                updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                        WifiManager.WIFI_AP_STATE_ENABLING, 0);
            }
        }
        mStateMachine.quitNow();
    }

    @Override
    public @Role int getRole() {
        return mRole;
    }

    @Override
    public void setRole(@Role int role) {
        // softap does not allow in-place switching of roles.
        Preconditions.checkState(mRole == ROLE_UNSPECIFIED);
        Preconditions.checkState(SOFTAP_ROLES.contains(role));
        mRole = role;
    }

    /**
     * Update AP capability. Called when carrier config or device resouce config changed.
     *
     * @param capability new AP capability.
     */
    public void updateCapability(@NonNull SoftApCapability capability) {
        mStateMachine.sendMessage(SoftApStateMachine.CMD_UPDATE_CAPABILITY, capability);
    }

    /**
     * Update AP configuration. Called when setting update config via
     * {@link WifiManager#setSoftApConfiguration(SoftApConfiguration)}
     *
     * @param config new AP config.
     */
    public void updateConfiguration(@NonNull SoftApConfiguration config) {
        mStateMachine.sendMessage(SoftApStateMachine.CMD_UPDATE_CONFIG, config);
    }

    /**
     * Dump info about this softap manager.
     */
    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("--Dump of SoftApManager--");

        pw.println("current StateMachine mode: " + getCurrentStateName());
        pw.println("mRole: " + mRole);
        pw.println("mApInterfaceName: " + mApInterfaceName);
        pw.println("mIfaceIsUp: " + mIfaceIsUp);
        pw.println("mSoftApCountryCode: " + mCountryCode);
        pw.println("mApConfig.targetMode: " + mApConfig.getTargetMode());
        SoftApConfiguration softApConfig = mApConfig.getSoftApConfiguration();
        pw.println("mApConfig.SoftApConfiguration.SSID: " + softApConfig.getSsid());
        pw.println("mApConfig.SoftApConfiguration.mBand: " + softApConfig.getBand());
        pw.println("mApConfig.SoftApConfiguration.hiddenSSID: " + softApConfig.isHiddenSsid());
        pw.println("mConnectedClients.size(): " + mConnectedClients.size());
        pw.println("mTimeoutEnabled: " + mTimeoutEnabled);
        pw.println("mCurrentSoftApInfo " + mCurrentSoftApInfo);
        pw.println("mStartTimestamp: " + mStartTimestamp);
        mStateMachine.dump(fd, pw, args);
    }

    private String getCurrentStateName() {
        IState currentState = mStateMachine.getCurrentState();

        if (currentState != null) {
            return currentState.getName();
        }

        return "StateMachine not active";
    }

    /**
     * Update AP state.
     *
     * @param newState     new AP state
     * @param currentState current AP state
     * @param reason       Failure reason if the new AP state is in failure state
     */
    private void updateApState(int newState, int currentState, int reason) {
        mSoftApCallback.onStateChanged(newState, reason);

        //send the AP state change broadcast
        final Intent intent = new Intent(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_STATE, newState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_AP_STATE, currentState);
        if (newState == WifiManager.WIFI_AP_STATE_FAILED) {
            //only set reason number when softAP start failed
            intent.putExtra(WifiManager.EXTRA_WIFI_AP_FAILURE_REASON, reason);
            if (mSoftApStartFailureDesc != null) {
                intent.putExtra(WifiManager.EXTRA_WIFI_AP_FAILURE_DESCRIPTION, mSoftApStartFailureDesc);
            }
        }

        intent.putExtra(WifiManager.EXTRA_WIFI_AP_INTERFACE_NAME, mDataInterfaceName);
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_MODE, mApConfig.getTargetMode());
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private int setMacAddress() {
        MacAddress mac = mApConfig.getSoftApConfiguration().getBssid();

        if (mac == null) {
            // If no BSSID is explicitly requested, (re-)configure the factory MAC address. Some
            // drivers may not support setting the MAC at all, so fail soft in this case.
            mac = mWifiNative.getFactoryMacAddress(mApInterfaceName);
            if (mac == null) {
                Log.e(TAG, "failed to get factory MAC address");
                return ERROR_GENERIC;
            }

            if (!mWifiNative.setMacAddress(mApInterfaceName, mac)) {
                Log.w(TAG, "failed to reset to factory MAC address; continuing with current MAC");
            }
            return SUCCESS;
        }

        // We're configuring a random/custom MAC address. In this case, driver support is mandatory.
        if (!mWifiNative.setMacAddress(mApInterfaceName, mac)) {
            Log.e(TAG, "failed to set explicitly requested MAC address");
            return ERROR_GENERIC;
        }
        return SUCCESS;
    }

    private int setCountryCode() {
        int band = mApConfig.getSoftApConfiguration().getBand();
        if (TextUtils.isEmpty(mCountryCode)) {
            if (band == SoftApConfiguration.BAND_5GHZ) {
                // Country code is mandatory for 5GHz band.
                Log.e(TAG, "Invalid country code, required for setting up soft ap in 5GHz");
                return ERROR_GENERIC;
            }
            // Absence of country code is not fatal for 2Ghz & Any band options.
            return SUCCESS;
        }

        if (!mWifiNative.setCountryCodeHal(
                mApInterfaceName, mCountryCode.toUpperCase(Locale.ROOT))) {
            if (band == SoftApConfiguration.BAND_5GHZ) {
                // Return an error if failed to set country code when AP is configured for
                // 5GHz band.
                Log.e(TAG, "Failed to set country code, required for setting up soft ap in 5GHz");
                return ERROR_GENERIC;
            }
            // Failure to set country code is not fatal for other band options.
        }
        return SUCCESS;
    }

    /**
     * Start a soft AP instance as configured.
     *
     * @return integer result code
     */
    private int startSoftAp() {
        SoftApConfiguration config = mApConfig.getSoftApConfiguration();
        if (config == null || config.getSsid() == null) {
            Log.e(TAG, "Unable to start soft AP without valid configuration");
            return ERROR_GENERIC;
        }

        Log.d(TAG, "band " + config.getBand() + " iface "
                + mApInterfaceName + " country " + mCountryCode);

        int result = setMacAddress();
        if (result != SUCCESS) {
            return result;
        }

        result = setCountryCode();
        if (result != SUCCESS) {
            return result;
        }

        if (config.getBand() == SoftApConfiguration.BAND_5GHZ
                && !mWifiNative.is5GhzBandSupported()) {
            mSoftApStartFailureDesc = WifiManager.WIFI_AP_FAILURE_DESC_NO_5GHZ_SUPPORT;
            Log.e(TAG, "Failed to start soft AP as 5Ghz band not supported");
            return ERROR_NO_CHANNEL;
        } else {
            mSoftApStartFailureDesc = "";
        }

        // Make a copy of configuration for updating AP band and channel.
        SoftApConfiguration.Builder localConfigBuilder = new SoftApConfiguration.Builder(config);

        boolean acsEnabled = mCurrentSoftApCapability.isFeatureSupported(
                SoftApCapability.SOFTAP_FEATURE_ACS_OFFLOAD);

        result = ApConfigUtil.updateApChannelConfig(
                mWifiNative, mContext.getResources(), mCountryCode, localConfigBuilder, config,
                acsEnabled);
        if (result != SUCCESS) {
            Log.e(TAG, "Failed to update AP band and channel");
            return result;
        }

        if (config.isHiddenSsid()) {
            Log.d(TAG, "SoftAP is a hidden network");
        }

        if (!ApConfigUtil.checkSupportAllConfiguration(config, mCurrentSoftApCapability)) {
            Log.d(TAG, "Unsupported Configuration detect! config = " + config);
            return ERROR_UNSUPPORTED_CONFIGURATION;
        }

        if (!mWifiNative.startSoftAp(mApInterfaceName,
                  localConfigBuilder.build(), mSoftApListener)) {
            Log.e(TAG, "Soft AP start failed");
            return ERROR_GENERIC;
        }

        mWifiDiagnostics.startLogging(mApInterfaceName);
        mStartTimestamp = FORMATTER.format(new Date(System.currentTimeMillis()));
        Log.d(TAG, "Soft AP is started ");

        return SUCCESS;
    }

    /**
     * Teardown soft AP and teardown the interface.
     */
    private void stopSoftAp() {
        if (mWifiApConfigStore.getDualSapStatus() && !mDualSapIfacesDestroyed) {
            mDualSapIfacesDestroyed = true;
            mWifiNative.teardownInterface(mdualApInterfaces[0]);
            mWifiNative.teardownInterface(mdualApInterfaces[1]);
        }
        mWifiDiagnostics.stopLogging(mApInterfaceName);
        mWifiNative.teardownInterface(mApInterfaceName);
        Log.d(TAG, "Soft AP is stopped");
    }

    private boolean checkSoftApClient(SoftApConfiguration config, WifiClient newClient) {
        if (!mCurrentSoftApCapability.isFeatureSupported(
                SoftApCapability.SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT)) {
            return true;
        }

        if (config.isClientControlByUserEnabled()
                && !mAllowedClientList.contains(newClient.getMacAddress())) {
            if (!mBlockedClientList.contains(newClient.getMacAddress())) {
                mSoftApCallback.onBlockedClientConnecting(newClient,
                        WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_BLOCKED_BY_USER);
            }
            Log.d(TAG, "Force disconnect for unauthorized client: " + newClient);
            mWifiNative.forceClientDisconnect(
                    mApInterfaceName, newClient.getMacAddress(),
                    WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_BLOCKED_BY_USER);
            return false;
        }
        int maxConfig = mCurrentSoftApCapability.getMaxSupportedClients();
        if (config.getMaxNumberOfClients() > 0) {
            maxConfig = Math.min(maxConfig, config.getMaxNumberOfClients());
        }

        if (mConnectedClients.size() >= maxConfig) {
            Log.i(TAG, "No more room for new client:" + newClient);
            mWifiNative.forceClientDisconnect(
                    mApInterfaceName, newClient.getMacAddress(),
                    WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_NO_MORE_STAS);
            mSoftApCallback.onBlockedClientConnecting(newClient,
                    WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_NO_MORE_STAS);
            return false;
        }
        return true;
    }

    private class SoftApStateMachine extends StateMachine {
        // Commands for the state machine.
        public static final int CMD_START = 0;
        public static final int CMD_FAILURE = 2;
        public static final int CMD_INTERFACE_STATUS_CHANGED = 3;
        public static final int CMD_ASSOCIATED_STATIONS_CHANGED = 4;
        public static final int CMD_NO_ASSOCIATED_STATIONS_TIMEOUT = 5;
        public static final int CMD_INTERFACE_DESTROYED = 7;
        public static final int CMD_INTERFACE_DOWN = 8;
        public static final int CMD_SOFT_AP_CHANNEL_SWITCHED = 9;
        public static final int CMD_UPDATE_CAPABILITY = 10;
        public static final int CMD_UPDATE_CONFIG = 11;
        public static final int CMD_CONNECTED_STATIONS = 12;
        public static final int CMD_DISCONNECTED_STATIONS = 13;
        public static final int CMD_DUAL_SAP_INTERFACE_DESTROYED = 50;

        private final State mIdleState = new IdleState();
        private final State mStartedState = new StartedState();

        private final InterfaceCallback mWifiNativeInterfaceCallback = new InterfaceCallback() {
            @Override
            public void onDestroyed(String ifaceName) {
                if (mDataInterfaceName != null && mDataInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_DESTROYED);
                }
            }

            @Override
            public void onUp(String ifaceName) {
                if (mDataInterfaceName != null && mDataInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_STATUS_CHANGED, 1);
                }
            }

            @Override
            public void onDown(String ifaceName) {
                if (mDataInterfaceName != null && mDataInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_STATUS_CHANGED, 0);
                }
            }
        };

        private final InterfaceCallback mWifiNativeDualIfaceCallback = new InterfaceCallback() {
            @Override
            public void onDestroyed(String ifaceName) {
                sendMessage(CMD_DUAL_SAP_INTERFACE_DESTROYED, ifaceName);
            }

            @Override
            public void onUp(String ifaceName) { }

            @Override
            public void onDown(String ifaceName) { }
        };

        private boolean validateDualSapSetupResult(int result) {
            if (result != SUCCESS) {
                int failureReason = WifiManager.SAP_START_FAILURE_GENERAL;
                if (result == ERROR_NO_CHANNEL) {
                    failureReason = WifiManager.SAP_START_FAILURE_NO_CHANNEL;
                }
                updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                              WifiManager.WIFI_AP_STATE_ENABLING,
                              failureReason);
                stopSoftAp();
                mWifiMetrics.incrementSoftApStartResult(false, failureReason);
                return false;
            }
            if (!mWifiNative.setHostapdParams("softap bridge up " +mApInterfaceName)) {
               Log.e(TAG, "Failed to set interface up " +mApInterfaceName);
               return false;
            }

            return true;
        }

        private boolean setupInterfacesForDualSoftApMode() {
            mdualApInterfaces[0] = mWifiNative.setupInterfaceForSoftApMode(
                    mWifiNativeDualIfaceCallback);
            mdualApInterfaces[1] = mWifiNative.setupInterfaceForSoftApMode(
                    mWifiNativeDualIfaceCallback);

            String bridgeIfacename = mWifiNative.setupInterfaceForBridgeMode(
                    mWifiNativeInterfaceCallback);

            mApInterfaceName = bridgeIfacename;
            if (TextUtils.isEmpty(mdualApInterfaces[0]) ||
                    TextUtils.isEmpty(mdualApInterfaces[1]) ||
                    TextUtils.isEmpty(mApInterfaceName)) {
                Log.e(TAG, "setup failure when creating dual ap interface(s).");
                stopSoftAp();
                updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                        WifiManager.WIFI_AP_STATE_DISABLED,
                        WifiManager.SAP_START_FAILURE_GENERAL);
                mWifiMetrics.incrementSoftApStartResult(false,
                        WifiManager.SAP_START_FAILURE_GENERAL);
                return false;
            }
            mDataInterfaceName = mWifiNative.getFstDataInterfaceName();
            if (TextUtils.isEmpty(mDataInterfaceName)) {
                mDataInterfaceName = mApInterfaceName;
            }
            updateApState(WifiManager.WIFI_AP_STATE_ENABLING,
                    WifiManager.WIFI_AP_STATE_DISABLED, 0);

            return true;
        }

        /**
         * Start Dual band soft AP.
         */
        private boolean setupForDualBandSoftApMode(SoftApConfiguration config) {
            if (!setupInterfacesForDualSoftApMode())
                return false;

            SoftApConfiguration localConfig;
            SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder(config);
            String bridgeIfacename = mApInterfaceName;

            mApInterfaceName = mdualApInterfaces[0];
            localConfig = configBuilder.setBand(SoftApConfiguration.BAND_2GHZ).build();
            mApConfig = new SoftApModeConfiguration(mApConfig.getTargetMode(), localConfig,
                    mCurrentSoftApCapability);
            int result = startSoftAp();
            if (result == SUCCESS) {
                localConfig = configBuilder.setBand(SoftApConfiguration.BAND_5GHZ).build();
                mApInterfaceName = mdualApInterfaces[1];
                mApConfig = new SoftApModeConfiguration(mApConfig.getTargetMode(), localConfig,
                        mCurrentSoftApCapability);
                result = startSoftAp();
            }

            mApInterfaceName = bridgeIfacename;

            return validateDualSapSetupResult(result);
        }

        /**
         * Start OWE transition soft AP.
         */
        private boolean setupForOweTransitionSoftApMode(SoftApConfiguration config) {
            if (!setupInterfacesForDualSoftApMode())
                return false;

            SoftApConfiguration.Builder oweConfigBuilder = new SoftApConfiguration.Builder(config);
            SoftApConfiguration.Builder openConfigBuilder = new SoftApConfiguration.Builder(config);

            String bridgeIfacename = mApInterfaceName;

            mApInterfaceName = mdualApInterfaces[0];
            oweConfigBuilder.setOweTransIfaceName(mdualApInterfaces[1]);

            // hashCode() generates integer hash for given string
            // As maximum string size of a integer is 12 bytes SSID size never crosses 32 bytes
            oweConfigBuilder.setSsid("OWE_" + config.getSsid().hashCode());
            oweConfigBuilder.setHiddenSsid(true);
            SoftApConfiguration oweConfig = oweConfigBuilder.build();
            Log.i(TAG, "Generated OWE SSID: " + oweConfig.getSsid());
            mApConfig = new SoftApModeConfiguration(mApConfig.getTargetMode(), oweConfig,
                    mCurrentSoftApCapability);
            int result = startSoftAp();
            if (result == SUCCESS) {
                mApInterfaceName = mdualApInterfaces[1];
                openConfigBuilder.setOweTransIfaceName(mdualApInterfaces[0]);
                openConfigBuilder.setPassphrase(null, SoftApConfiguration.SECURITY_TYPE_OPEN);
                SoftApConfiguration openConfig = openConfigBuilder.build();
                mApConfig = new SoftApModeConfiguration(mApConfig.getTargetMode(), openConfig,
                        mCurrentSoftApCapability);
                result = startSoftAp();
            }

            mApInterfaceName = bridgeIfacename;

            return validateDualSapSetupResult(result);
        }

        SoftApStateMachine(Looper looper) {
            super(TAG, looper);

            addState(mIdleState);
            addState(mStartedState);

            setInitialState(mIdleState);
            start();
        }

        private class IdleState extends State {
            @Override
            public void enter() {
                mApInterfaceName = null;
                mDataInterfaceName = null;
                mIfaceIsUp = false;
                mIfaceIsDestroyed = false;
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_START:
                        SoftApConfiguration config = (SoftApConfiguration) message.obj;
                        if (config != null && config.getBand() == SoftApConfiguration.BAND_ANY) {
                            if (!setupForDualBandSoftApMode(config)) {
                                Log.d(TAG, "Dual band sap start failed");
                                break;
                            }
                            transitionTo(mStartedState);
                            break;
                        } else if (config != null && config.getSecurityType() == SoftApConfiguration.SECURITY_TYPE_OWE) {
                            if (!setupForOweTransitionSoftApMode(config)) {
                                Log.d(TAG, "OWE transition sap start failed");
                                break;
                            }
                            transitionTo(mStartedState);
                            break;
                        }

                        mApInterfaceName = mWifiNative.setupInterfaceForSoftApMode(
                                mWifiNativeInterfaceCallback);
                        if (TextUtils.isEmpty(mApInterfaceName)) {
                            Log.e(TAG, "setup failure when creating ap interface.");
                            updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                    WifiManager.WIFI_AP_STATE_DISABLED,
                                    WifiManager.SAP_START_FAILURE_GENERAL);
                            mWifiMetrics.incrementSoftApStartResult(
                                    false, WifiManager.SAP_START_FAILURE_GENERAL);
                            mModeListener.onStartFailure();
                            break;
                        }
                        mDataInterfaceName = mWifiNative.getFstDataInterfaceName();
                        if (TextUtils.isEmpty(mDataInterfaceName)) {
                            mDataInterfaceName = mApInterfaceName;
                        }

                        mSoftApNotifier.dismissSoftApShutDownTimeoutExpiredNotification();
                        updateApState(WifiManager.WIFI_AP_STATE_ENABLING,
                                WifiManager.WIFI_AP_STATE_DISABLED, 0);
                        int result = startSoftAp();
                        if (result != SUCCESS) {
                            int failureReason = WifiManager.SAP_START_FAILURE_GENERAL;
                            if (result == ERROR_NO_CHANNEL) {
                                failureReason = WifiManager.SAP_START_FAILURE_NO_CHANNEL;
                            } else if (result == ERROR_UNSUPPORTED_CONFIGURATION) {
                                failureReason = WifiManager
                                        .SAP_START_FAILURE_UNSUPPORTED_CONFIGURATION;
                            }
                            updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                    WifiManager.WIFI_AP_STATE_ENABLING,
                                    failureReason);
                            stopSoftAp();
                            mWifiMetrics.incrementSoftApStartResult(false, failureReason);
                            mModeListener.onStartFailure();
                            break;
                        }
                        transitionTo(mStartedState);
                        break;
                    case CMD_UPDATE_CAPABILITY:
                        // Capability should only changed by carrier requirement. Only apply to
                        // Tether Mode
                        if (mApConfig.getTargetMode() ==  WifiManager.IFACE_IP_MODE_TETHERED) {
                            SoftApCapability capability = (SoftApCapability) message.obj;
                            mCurrentSoftApCapability = new SoftApCapability(capability);
                        }
                        break;
                    case CMD_UPDATE_CONFIG:
                        SoftApConfiguration newConfig = (SoftApConfiguration) message.obj;
                        Log.d(TAG, "Configuration changed to " + newConfig);
                        mApConfig = new SoftApModeConfiguration(mApConfig.getTargetMode(),
                                newConfig, mCurrentSoftApCapability);
                        mBlockedClientList = new HashSet<>(newConfig.getBlockedClientList());
                        mAllowedClientList = new HashSet<>(newConfig.getAllowedClientList());
                        mTimeoutEnabled = newConfig.isAutoShutdownEnabled();
                        break;
                    default:
                        // Ignore all other commands.
                        break;
                }

                return HANDLED;
            }
        }

        private class StartedState extends State {
            private WakeupMessage mSoftApTimeoutMessage;

            private void scheduleTimeoutMessage() {
                if (!mTimeoutEnabled || mQCNumAssociatedStations != 0) {
                    cancelTimeoutMessage();
                    return;
                }
                int timeout = mApConfig.getSoftApConfiguration().getShutdownTimeoutMillis();
                if (timeout == 0) {
                    timeout =  mContext.getResources().getInteger(
                            R.integer.config_wifiFrameworkSoftApShutDownTimeoutMilliseconds);
                }
                mSoftApTimeoutMessage.schedule(SystemClock.elapsedRealtime()
                        + timeout);
                Log.d(TAG, "Timeout message scheduled, delay = "
                        + timeout);
            }

            private void cancelTimeoutMessage() {
                mSoftApTimeoutMessage.cancel();
                Log.d(TAG, "Timeout message canceled");
            }

            /**
             * When configuration changed, it need to force some clients disconnect to match the
             * configuration.
             */
            private void updateClientConnection() {
                if (!mCurrentSoftApCapability.isFeatureSupported(
                        SoftApCapability.SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT)) {
                    return;
                }
                final int maxAllowedClientsByHardwareAndCarrier =
                        mCurrentSoftApCapability.getMaxSupportedClients();
                final int userApConfigMaxClientCount =
                        mApConfig.getSoftApConfiguration().getMaxNumberOfClients();
                int finalMaxClientCount = maxAllowedClientsByHardwareAndCarrier;
                if (userApConfigMaxClientCount > 0) {
                    finalMaxClientCount = Math.min(userApConfigMaxClientCount,
                            maxAllowedClientsByHardwareAndCarrier);
                }
                int targetDisconnectClientNumber = mConnectedClients.size() - finalMaxClientCount;
                List<WifiClient> allowedConnectedList = new ArrayList<>();
                if (mApConfig.getSoftApConfiguration().isClientControlByUserEnabled()) {
                    // Check allow list first
                    Iterator<WifiClient> iterator = mConnectedClients.iterator();
                    while (iterator.hasNext()) {
                        WifiClient client = iterator.next();
                        if (mAllowedClientList.contains(client.getMacAddress())) {
                            allowedConnectedList.add(client);
                        } else {
                            Log.d(TAG, "Force disconnect for not allowed client: " + client);
                            mWifiNative.forceClientDisconnect(
                                    mApInterfaceName, client.getMacAddress(),
                                    WifiManager
                                    .SAP_CLIENT_BLOCK_REASON_CODE_BLOCKED_BY_USER);
                            targetDisconnectClientNumber--;
                        }
                    }
                } else {
                    allowedConnectedList = new ArrayList<>(mConnectedClients);
                }
                if (targetDisconnectClientNumber > 0) {
                    Iterator<WifiClient> allowedClientIterator = allowedConnectedList.iterator();
                    while (allowedClientIterator.hasNext()) {
                        if (targetDisconnectClientNumber == 0) break;
                        WifiClient allowedClient = allowedClientIterator.next();
                        Log.d(TAG, "Force disconnect for client due to no more room: "
                                + allowedClient);
                        mWifiNative.forceClientDisconnect(
                                mApInterfaceName, allowedClient.getMacAddress(),
                                WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_NO_MORE_STAS);
                        targetDisconnectClientNumber--;
                    }
                }
            }

            /**
             * Set stations associated with this soft AP
             * @param client The station for which connection state changed.
             * @param isConnected True for the connection changed to connect, otherwise false.
             */
            private void updateConnectedClients(WifiClient client, boolean isConnected) {
                if (client == null) {
                    return;
                }

                int index = mConnectedClients.indexOf(client);
                if ((index != -1) == isConnected) {
                    Log.e(TAG, "Drop client connection event, client "
                            + client + "isConnected: " + isConnected
                            + " , duplicate event or client is blocked");
                    return;
                }
                if (isConnected) {
                    boolean isAllow = checkSoftApClient(
                            mApConfig.getSoftApConfiguration(), client);
                    if (isAllow) {
                        mConnectedClients.add(client);
                    } else {
                        return;
                    }
                } else {
                    mConnectedClients.remove(index);
                }

                Log.d(TAG, "The connected wifi stations have changed with count: "
                        + mConnectedClients.size() + ": " + mConnectedClients);

                if (mSoftApCallback != null) {
                    mSoftApCallback.onConnectedClientsChanged(mConnectedClients);
                } else {
                    Log.e(TAG,
                            "SoftApCallback is null. Dropping ConnectedClientsChanged event."
                    );
                }

                mWifiMetrics.addSoftApNumAssociatedStationsChangedEvent(
                        mConnectedClients.size(), mApConfig.getTargetMode());

                scheduleTimeoutMessage();
            }

            /**
             * Set New connected stations with this soft AP
             * @param Macaddr Mac address of connected stations
             */
            private void setConnectedStations(String Macaddr) {

                mQCNumAssociatedStations++;
                if (mSoftApCallback != null) {
                    mSoftApCallback.onStaConnected(Macaddr,mQCNumAssociatedStations);
                } else {
                    Log.e(TAG, "SoftApCallback is null. Dropping onStaConnected event.");
                }
                if (mQCNumAssociatedStations > 0)
                    cancelTimeoutMessage();
            }

            /**
             * Set Disconnected stations with this soft AP
             * @param Macaddr Mac address of Disconnected stations
             */
            private void setDisConnectedStations(String Macaddr) {

                if (mQCNumAssociatedStations > 0)
                     mQCNumAssociatedStations--;
                if (mSoftApCallback != null) {
                    mSoftApCallback.onStaDisconnected(Macaddr, mQCNumAssociatedStations);
                } else {
                    Log.e(TAG, "SoftApCallback is null. Dropping onStaDisconnected event.");
                }
                if (mQCNumAssociatedStations == 0)
                    scheduleTimeoutMessage();
            }

            private void setSoftApChannel(int freq, @WifiAnnotations.Bandwidth int apBandwidth) {
                Log.d(TAG, "Channel switched. Frequency: " + freq
                        + " Bandwidth: " + apBandwidth);

                if (freq == mCurrentSoftApInfo.getFrequency()
                        && apBandwidth == mCurrentSoftApInfo.getBandwidth()) {
                    return; // no change
                }

                mCurrentSoftApInfo.setFrequency(freq);
                mCurrentSoftApInfo.setBandwidth(apBandwidth);
                mSoftApCallback.onInfoChanged(mCurrentSoftApInfo);

                // ignore invalid freq and softap disable case for metrics
                if (freq > 0 && apBandwidth != SoftApInfo.CHANNEL_WIDTH_INVALID) {
                    mWifiMetrics.addSoftApChannelSwitchedEvent(mCurrentSoftApInfo.getFrequency(),
                            mCurrentSoftApInfo.getBandwidth(), mApConfig.getTargetMode());
                    updateUserBandPreferenceViolationMetricsIfNeeded();
                }
            }

            private void onUpChanged(boolean isUp) {
                if (isUp == mIfaceIsUp) {
                    return;  // no change
                }

                mIfaceIsUp = isUp;
                if (isUp) {
                    Log.d(TAG, "SoftAp is ready for use");
                    updateApState(WifiManager.WIFI_AP_STATE_ENABLED,
                            WifiManager.WIFI_AP_STATE_ENABLING, 0);
                    mModeListener.onStarted();
                    mWifiMetrics.incrementSoftApStartResult(true, 0);
                    if (mSoftApCallback != null) {
                        mSoftApCallback.onConnectedClientsChanged(mConnectedClients);
                        mSoftApCallback.onStaConnected("", mQCNumAssociatedStations);
                    }
                } else {
                    // the interface was up, but goes down
                    sendMessage(CMD_INTERFACE_DOWN);
                }
                mWifiMetrics.addSoftApUpChangedEvent(isUp, mApConfig.getTargetMode());
            }

            @Override
            public void enter() {
                mIfaceIsUp = false;
                mIfaceIsDestroyed = false;
                onUpChanged(mWifiNative.isInterfaceUp(mApInterfaceName));
                onUpChanged(mWifiNative.isInterfaceUp(mDataInterfaceName));

                Handler handler = mStateMachine.getHandler();
                mSoftApTimeoutMessage = new WakeupMessage(mContext, handler,
                        SOFT_AP_SEND_MESSAGE_TIMEOUT_TAG,
                        SoftApStateMachine.CMD_NO_ASSOCIATED_STATIONS_TIMEOUT);

                mSarManager.setSapWifiState(WifiManager.WIFI_AP_STATE_ENABLED);

                Log.d(TAG, "Resetting connected clients on start");
                mConnectedClients.clear();
                mQCNumAssociatedStations = 0;
                scheduleTimeoutMessage();
            }

            @Override
            public void exit() {
                if (!mIfaceIsDestroyed) {
                    stopSoftAp();
                }

                Log.d(TAG, "Resetting num stations on stop");
                mQCNumAssociatedStations = 0;
                if (mConnectedClients.size() != 0) {
                    mConnectedClients.clear();
                    if (mSoftApCallback != null) {
                        mSoftApCallback.onConnectedClientsChanged(mConnectedClients);
                    }
                    mWifiMetrics.addSoftApNumAssociatedStationsChangedEvent(
                            0, mApConfig.getTargetMode());
                }
                cancelTimeoutMessage();

                // Need this here since we are exiting |Started| state and won't handle any
                // future CMD_INTERFACE_STATUS_CHANGED events after this point
                mWifiMetrics.addSoftApUpChangedEvent(false, mApConfig.getTargetMode());
                updateApState(WifiManager.WIFI_AP_STATE_DISABLED,
                        WifiManager.WIFI_AP_STATE_DISABLING, 0);

                mSarManager.setSapWifiState(WifiManager.WIFI_AP_STATE_DISABLED);
                mApInterfaceName = null;
                mDataInterfaceName = null;
                mIfaceIsUp = false;
                mIfaceIsDestroyed = false;
                mRole = ROLE_UNSPECIFIED;
                mStateMachine.quitNow();
                mModeListener.onStopped();
                setSoftApChannel(0, SoftApInfo.CHANNEL_WIDTH_INVALID);
            }

            private void updateUserBandPreferenceViolationMetricsIfNeeded() {
                int band = mApConfig.getSoftApConfiguration().getBand();
                boolean bandPreferenceViolated =
                        (ScanResult.is24GHz(mCurrentSoftApInfo.getFrequency())
                            && !ApConfigUtil.containsBand(band,
                                    SoftApConfiguration.BAND_2GHZ))
                        || (ScanResult.is5GHz(mCurrentSoftApInfo.getFrequency())
                            && !ApConfigUtil.containsBand(band,
                                    SoftApConfiguration.BAND_5GHZ))
                        || (ScanResult.is6GHz(mCurrentSoftApInfo.getFrequency())
                            && !ApConfigUtil.containsBand(band,
                                    SoftApConfiguration.BAND_6GHZ));

                if (bandPreferenceViolated) {
                    Log.e(TAG, "Channel does not satisfy user band preference: "
                            + mCurrentSoftApInfo.getFrequency());
                    mWifiMetrics.incrementNumSoftApUserBandPreferenceUnsatisfied();
                }
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_ASSOCIATED_STATIONS_CHANGED:
                        if (!(message.obj instanceof NativeWifiClient)) {
                            Log.e(TAG, "Invalid type returned for"
                                    + " CMD_ASSOCIATED_STATIONS_CHANGED");
                            break;
                        }
                        NativeWifiClient nativeClient = (NativeWifiClient) message.obj;
                        boolean isConnected = (message.arg1 == 1);
                        if (nativeClient != null && nativeClient.getMacAddress() != null) {
                            WifiClient client = new WifiClient(nativeClient.getMacAddress());
                            Log.d(TAG, "CMD_ASSOCIATED_STATIONS_CHANGED, Client: "
                                    + nativeClient.getMacAddress().toString() + " isConnected: "
                                    + isConnected);
                            updateConnectedClients(client, isConnected);
                        }
                        break;
                    case CMD_SOFT_AP_CHANNEL_SWITCHED:
                        if (message.arg1 < 0) {
                            Log.e(TAG, "Invalid ap channel frequency: " + message.arg1);
                            break;
                        }
                        setSoftApChannel(message.arg1, message.arg2);
                        break;
                    case CMD_CONNECTED_STATIONS:
                        if (message.obj == null) {
                            Log.e(TAG, "Invalid Macaddr of connected station: " + message.obj);
                            break;
                        }
                        Log.d(TAG, "Setting Macaddr of stations on CMD_CONNECTED_STATIONS");
                        setConnectedStations((String) message.obj);
                        break;
                    case CMD_DISCONNECTED_STATIONS:
                        if (message.obj == null) {
                            Log.e(TAG, "Invalid Macaddr of disconnected station: " + message.obj);
                            break;
                        }
                        Log.d(TAG, "Setting Macaddr of stations on CMD_DISCONNECTED_STATIONS");
                        setDisConnectedStations((String) message.obj);
                        break;
                    case CMD_INTERFACE_STATUS_CHANGED:
                        boolean isUp = message.arg1 == 1;
                        onUpChanged(isUp);
                        break;
                    case CMD_START:
                        // Already started, ignore this command.
                        break;
                    case CMD_NO_ASSOCIATED_STATIONS_TIMEOUT:
                        if (!mTimeoutEnabled) {
                            Log.wtf(TAG, "Timeout message received while timeout is disabled."
                                    + " Dropping.");
                            break;
                        }
                        if (mConnectedClients.size() != 0) {
                            Log.wtf(TAG, "Timeout message received but has clients. Dropping.");
                            break;
                        }
                        mSoftApNotifier.showSoftApShutDownTimeoutExpiredNotification();
                        Log.i(TAG, "Timeout message received. Stopping soft AP.");
                        updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                                WifiManager.WIFI_AP_STATE_ENABLED, 0);
                        transitionTo(mIdleState);
                        break;
                    case CMD_INTERFACE_DESTROYED:
                        //teardown Dual SAP interfaces if required
                        if (mWifiApConfigStore.getDualSapStatus() && !mDualSapIfacesDestroyed) {
                            Log.d(TAG, "Bridge inteface destroyed, Teardown dual intefaces");
                            mDualSapIfacesDestroyed = true;
                            mWifiNative.teardownInterface(mdualApInterfaces[0]);
                            mWifiNative.teardownInterface(mdualApInterfaces[1]);
                        }
                        Log.d(TAG, "Interface(s) was cleanly destroyed.");
                        updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                                WifiManager.WIFI_AP_STATE_ENABLED, 0);
                        mIfaceIsDestroyed = true;
                        transitionTo(mIdleState);
                        break;
                    case CMD_DUAL_SAP_INTERFACE_DESTROYED:
                        // one of the dual interface is destroyed by native layers. trigger full cleanup.
                        if (!mDualSapIfacesDestroyed) {
                            String ifaceName = (String) message.obj;
                            Log.d(TAG, "One of Dual interface ("+ifaceName+") destroyed. trigger cleanup");
                            // teardown other dual interface and bridge interface.
                            mDualSapIfacesDestroyed = true;
                            if (ifaceName.equals(mdualApInterfaces[0])) {
                               mWifiNative.teardownInterface(mdualApInterfaces[1]);
                            } else if (ifaceName.equals(mdualApInterfaces[1])) {
                               mWifiNative.teardownInterface(mdualApInterfaces[0]);
                            }
                            mWifiNative.teardownInterface(mApInterfaceName);
                        }
                        break;
                    case CMD_FAILURE:
                        Log.w(TAG, "hostapd failure, stop and report failure");
                        /* fall through */
                    case CMD_INTERFACE_DOWN:
                        Log.w(TAG, "interface error, stop and report failure");
                        updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                WifiManager.WIFI_AP_STATE_ENABLED,
                                WifiManager.SAP_START_FAILURE_GENERAL);
                        updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                                WifiManager.WIFI_AP_STATE_FAILED, 0);
                        transitionTo(mIdleState);
                        break;
                    case CMD_UPDATE_CAPABILITY:
                        // Capability should only changed by carrier requirement. Only apply to
                        // Tether Mode
                        if (mApConfig.getTargetMode() ==  WifiManager.IFACE_IP_MODE_TETHERED) {
                            SoftApCapability capability = (SoftApCapability) message.obj;
                            mCurrentSoftApCapability = new SoftApCapability(capability);
                            updateClientConnection();
                        }
                        break;
                    case CMD_UPDATE_CONFIG:
                        SoftApConfiguration newConfig = (SoftApConfiguration) message.obj;
                        SoftApConfiguration currentConfig = mApConfig.getSoftApConfiguration();
                        if (mIsRandomizeBssid) {
                            // Current bssid is ramdon because unset. Set back to null..
                            currentConfig = new SoftApConfiguration.Builder(currentConfig)
                                    .setBssid(null)
                                    .build();
                        }
                        if (!ApConfigUtil.checkConfigurationChangeNeedToRestart(
                                currentConfig, newConfig)) {
                            Log.d(TAG, "Configuration changed to " + newConfig);
                            boolean needRescheduleTimer =
                                    mApConfig.getSoftApConfiguration().getShutdownTimeoutMillis()
                                    != newConfig.getShutdownTimeoutMillis()
                                    || mTimeoutEnabled != newConfig.isAutoShutdownEnabled();
                            mBlockedClientList = new HashSet<>(newConfig.getBlockedClientList());
                            mAllowedClientList = new HashSet<>(newConfig.getAllowedClientList());
                            mTimeoutEnabled = newConfig.isAutoShutdownEnabled();
                            mApConfig = new SoftApModeConfiguration(mApConfig.getTargetMode(),
                                    newConfig, mCurrentSoftApCapability);
                            updateClientConnection();
                            if (needRescheduleTimer) {
                                cancelTimeoutMessage();
                                scheduleTimeoutMessage();
                            }
                        } else {
                            Log.d(TAG, "Ignore the config: " + newConfig
                                    + " update since it requires restart");
                        }
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }
    }
}
