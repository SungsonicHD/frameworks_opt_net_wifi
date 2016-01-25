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
 * limitations under the License
 */

package com.android.server.wifi;

import static org.hamcrest.CoreMatchers.not;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyObject;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.intThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.os.UserHandle;
import android.test.AndroidTestCase;

import com.android.server.net.DelayedDiskWrite;
import com.android.server.wifi.MockAnswerUtil.AnswerWithArguments;
import com.android.server.wifi.hotspot2.omadm.MOManager;
import com.android.server.wifi.hotspot2.pps.Credential;
import com.android.server.wifi.hotspot2.pps.HomeSP;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unit tests for {@link com.android.server.wifi.WifiConfigStore}.
 */
public class WifiConfigStoreTest extends AndroidTestCase {
    private static final List<WifiConfiguration> CONFIGS = Arrays.asList(
            WifiConfigurationUtil.generateWifiConfig(
                    0, 1000000, "\"red\"", true, true, null, null),
            WifiConfigurationUtil.generateWifiConfig(
                    1, 1000001, "\"green\"", true, true, "example.com", "Green"),
            WifiConfigurationUtil.generateWifiConfig(
                    2, 1100000, "\"blue\"", false, true, "example.org", "Blue"));

    private static final int[] USER_IDS = {0, 10, 11};
    private static final Map<Integer, List<WifiConfiguration>> VISIBLE_CONFIGS = new HashMap<>();
    static {
        for (int userId : USER_IDS) {
            List<WifiConfiguration> configs = new ArrayList<>();
            for (int i = 0; i < CONFIGS.size(); ++i) {
                if (CONFIGS.get(i).isVisibleToUser(userId)) {
                    configs.add(CONFIGS.get(i));
                }
            }
            VISIBLE_CONFIGS.put(userId, configs);
        }
    }

    @Mock private Context mContext;
    @Mock private WifiStateMachine mWifiStateMachine;
    @Mock private WifiNative mWifiNative;
    @Mock private FrameworkFacade mFrameworkFacade;
    @Mock private DelayedDiskWrite mWriter;
    @Mock private MOManager mMOManager;
    private WifiConfigStore mConfigStore;
    private ConfigurationMap mConfiguredNetworks;
    public byte[] mNetworkHistory;

    @Override
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        final Context realContext = getContext();
        when(mContext.getPackageName()).thenReturn(realContext.getPackageName());
        when(mContext.getResources()).thenReturn(realContext.getResources());
        when(mContext.getPackageManager()).thenReturn(realContext.getPackageManager());

        when(mWifiStateMachine.getCurrentUserId()).thenReturn(UserHandle.USER_SYSTEM);

        mConfigStore = new WifiConfigStore(mContext, mWifiStateMachine, mWifiNative,
                mFrameworkFacade);

        final Field configuredNetworksField =
                WifiConfigStore.class.getDeclaredField("mConfiguredNetworks");
        configuredNetworksField.setAccessible(true);
        mConfiguredNetworks = (ConfigurationMap) configuredNetworksField.get(mConfigStore);

        // Intercept writes to networkHistory.txt.
        doAnswer(new AnswerWithArguments<Void>() {
            public void answer(String filePath, DelayedDiskWrite.Writer writer) throws Exception {
                final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                final DataOutputStream stream = new DataOutputStream(buffer);
                writer.onWriteCalled(stream);
                stream.close();
                mNetworkHistory = buffer.toByteArray();
            }}).when(mWriter).write(anyString(), (DelayedDiskWrite.Writer) anyObject());
        final Field writerField = WifiConfigStore.class.getSuperclass().getDeclaredField("mWriter");
        writerField.setAccessible(true);
        writerField.set(mConfigStore, mWriter);

        when(mMOManager.isEnabled()).thenReturn(true);
        final Field moManagerField = WifiConfigStore.class.getDeclaredField("mMOManager");
        moManagerField.setAccessible(true);
        moManagerField.set(mConfigStore, mMOManager);
    }

    private void switchUser(int newUserId) {
        when(mWifiStateMachine.getCurrentUserId()).thenReturn(newUserId);
        mConfigStore.handleUserSwitch();
    }

    private void switchUserToCreatorOf(WifiConfiguration config) {
        switchUser(UserHandle.getUserId(config.creatorUid));
    }

    private void addNetworks() throws Exception {
        final int originalUserId = mWifiStateMachine.getCurrentUserId();

        when(mWifiNative.setNetworkVariable(anyInt(), anyString(), anyString())).thenReturn(true);
        when(mWifiNative.setNetworkExtra(anyInt(), anyString(), (Map<String, String>) anyObject()))
                .thenReturn(true);
        for (int i = 0; i < CONFIGS.size(); ++i) {
            assertEquals(i, CONFIGS.get(i).networkId);
            switchUserToCreatorOf(CONFIGS.get(i));
            final WifiConfiguration config = new WifiConfiguration(CONFIGS.get(i));
            config.networkId = -1;
            when(mWifiNative.addNetwork()).thenReturn(i);
            when(mWifiNative.getNetworkVariable(i, WifiConfiguration.ssidVarName))
                .thenReturn(encodeConfigSSID(CONFIGS.get(i)));
            mConfigStore.saveNetwork(config, config.creatorUid);
        }

        switchUser(originalUserId);
    }

    private String encodeConfigSSID(WifiConfiguration config) throws Exception {
        return new BigInteger(1, config.SSID.substring(1, config.SSID.length() - 1)
                .getBytes("UTF-8")).toString(16);
    }

    private WifiNative createNewWifiNativeMock() throws Exception {
        final WifiNative wifiNative = mock(WifiNative.class);
        final Field wifiNativeField = WifiConfigStore.class.getDeclaredField("mWifiNative");
        wifiNativeField.setAccessible(true);
        wifiNativeField.set(mConfigStore, wifiNative);
        return wifiNative;
    }

    /**
     * Verifies that getConfiguredNetworksSize() returns the number of network configurations
     * visible to the current user.
     */
    public void testGetConfiguredNetworksSize() throws Exception {
        addNetworks();
        for (Map.Entry<Integer, List<WifiConfiguration>> entry : VISIBLE_CONFIGS.entrySet()) {
            switchUser(entry.getKey());
            assertEquals(entry.getValue().size(), mConfigStore.getConfiguredNetworksSize());
        }
    }

    private void verifyNetworkConfig(WifiConfiguration expectedConfig,
            WifiConfiguration actualConfig) {
        assertNotNull(actualConfig);
        assertEquals(expectedConfig.SSID, actualConfig.SSID);
        assertEquals(expectedConfig.FQDN, actualConfig.FQDN);
        assertEquals(expectedConfig.providerFriendlyName,
                actualConfig.providerFriendlyName);
        assertEquals(expectedConfig.configKey(), actualConfig.configKey(false));
    }

    private void verifyNetworkConfigs(Collection<WifiConfiguration> expectedConfigs,
            Collection<WifiConfiguration> actualConfigs) {
        assertEquals(expectedConfigs.size(), actualConfigs.size());
        for (WifiConfiguration expectedConfig : expectedConfigs) {
            WifiConfiguration actualConfig = null;
            // Find the network configuration to test (assume that |actualConfigs| contains them in
            // undefined order).
            for (final WifiConfiguration candidate : actualConfigs) {
                if (candidate.networkId == expectedConfig.networkId) {
                    actualConfig = candidate;
                    break;
                }
            }
            verifyNetworkConfig(expectedConfig, actualConfig);
        }
    }

    /**
     * Verifies that getConfiguredNetworksSize() returns the network configurations visible to the
     * current user.
     */
    public void testGetConfiguredNetworks() throws Exception {
        addNetworks();
        for (Map.Entry<Integer, List<WifiConfiguration>> entry : VISIBLE_CONFIGS.entrySet()) {
            switchUser(entry.getKey());
            verifyNetworkConfigs(entry.getValue(), mConfigStore.getConfiguredNetworks());
        }
    }

    /**
     * Verifies that getPrivilegedConfiguredNetworks() returns the network configurations visible to
     * the current user.
     */
    public void testGetPrivilegedConfiguredNetworks() throws Exception {
        addNetworks();
        for (Map.Entry<Integer, List<WifiConfiguration>> entry : VISIBLE_CONFIGS.entrySet()) {
            switchUser(entry.getKey());
            verifyNetworkConfigs(entry.getValue(), mConfigStore.getPrivilegedConfiguredNetworks());
        }
    }

    /**
     * Verifies that getWifiConfiguration(int netId) can be used to access network configurations
     * visible to the current user only.
     */
    public void testGetWifiConfigurationByNetworkId() throws Exception {
        addNetworks();
        for (int userId : USER_IDS) {
            switchUser(userId);
            for (WifiConfiguration expectedConfig: CONFIGS) {
                final WifiConfiguration actualConfig =
                        mConfigStore.getWifiConfiguration(expectedConfig.networkId);
                if (expectedConfig.isVisibleToUser(userId)) {
                    verifyNetworkConfig(expectedConfig, actualConfig);
                } else {
                    assertNull(actualConfig);
                }
            }
        }
    }

    /**
     * Verifies that getWifiConfiguration(String key) can be used to access network configurations
     * visible to the current user only.
     */
    public void testGetWifiConfigurationByConfigKey() throws Exception {
        addNetworks();
        for (int userId : USER_IDS) {
            switchUser(userId);
            for (WifiConfiguration expectedConfig: CONFIGS) {
                final WifiConfiguration actualConfig =
                        mConfigStore.getWifiConfiguration(expectedConfig.configKey());
                if (expectedConfig.isVisibleToUser(userId)) {
                    verifyNetworkConfig(expectedConfig, actualConfig);
                } else {
                    assertNull(actualConfig);
                }
            }
        }
    }

    /**
     * Verifies that enableAllNetworks() enables all temporarily disabled network configurations
     * visible to the current user.
     */
    public void testEnableAllNetworks() throws Exception {
        addNetworks();
        when(mWifiNative.enableNetwork(anyInt(), anyBoolean())).thenReturn(true);
        for (int userId : USER_IDS) {
            switchUser(userId);

            for (WifiConfiguration config : mConfiguredNetworks.valuesForAllUsers()) {
                final WifiConfiguration.NetworkSelectionStatus status =
                        config.getNetworkSelectionStatus();
                status.setNetworkSelectionStatus(WifiConfiguration.NetworkSelectionStatus
                        .NETWORK_SELECTION_TEMPORARY_DISABLED);
                status.setNetworkSelectionDisableReason(
                        WifiConfiguration.NetworkSelectionStatus.DISABLED_AUTHENTICATION_FAILURE);
                status.setDisableTime(System.currentTimeMillis() - 60 * 60 * 1000);
            }

            mConfigStore.enableAllNetworks();

            for (WifiConfiguration config : mConfiguredNetworks.valuesForAllUsers()) {
                assertEquals(config.isVisibleToUser(userId),
                        config.getNetworkSelectionStatus().isNetworkEnabled());
            }
        }
    }

    /**
     * Verifies that selectNetwork() disables all network configurations visible to the current user
     * except the selected one.
     */
    public void testSelectNetwork() throws Exception {
        addNetworks();

        for (int userId : USER_IDS) {
            switchUser(userId);

            for (WifiConfiguration config : mConfiguredNetworks.valuesForAllUsers()) {
                // Enable all network configurations.
                for (WifiConfiguration config2 : mConfiguredNetworks.valuesForAllUsers()) {
                    config2.status = WifiConfiguration.Status.ENABLED;
                }

                // Try to select a network configuration.
                final WifiNative wifiNative = createNewWifiNativeMock();
                final boolean success =
                        mConfigStore.selectNetwork(config, false, config.creatorUid);
                if (!config.isVisibleToUser(userId)) {
                    // If the network configuration is not visible to the current user, verify that
                    // nothing changed.
                    assertFalse(success);
                    verify(wifiNative, never()).selectNetwork(anyInt());
                    verify(wifiNative, never()).enableNetwork(anyInt(), anyBoolean());
                    for (WifiConfiguration config2 : mConfiguredNetworks.valuesForAllUsers()) {
                        assertEquals(WifiConfiguration.Status.ENABLED, config2.status);
                    }
                } else {
                    // If the network configuration is visible to the current user, verify that it
                    // was enabled and all other network configurations visible to the user were
                    // disabled.
                    assertTrue(success);
                    verify(wifiNative).selectNetwork(config.networkId);
                    verify(wifiNative, never()).selectNetwork(intThat(not(config.networkId)));
                    verify(wifiNative).enableNetwork(config.networkId, true);
                    verify(wifiNative, never()).enableNetwork(config.networkId, false);
                    verify(wifiNative, never()).enableNetwork(intThat(not(config.networkId)),
                            anyBoolean());
                    for (WifiConfiguration config2 : mConfiguredNetworks.valuesForAllUsers()) {
                        if (config2.isVisibleToUser(userId)
                                && config2.networkId != config.networkId) {
                            assertEquals(WifiConfiguration.Status.DISABLED, config2.status);
                        } else {
                            assertEquals(WifiConfiguration.Status.ENABLED, config2.status);
                        }
                    }
                }
            }
        }
    }

    /**
     * Verifies that saveNetwork() correctly stores a network configuration in wpa_supplicant
     * variables and the networkHistory.txt file.
     * TODO: Test all variables. Currently, only the following variables are tested:
     * - In the wpa_supplicant: "ssid", "id_str"
     * - In networkHistory.txt: "CONFIG", "CREATOR_UID_KEY", "SHARED"
     */
    private void verifySaveNetwork(int network) throws Exception {
        // Switch to the correct user.
        switchUserToCreatorOf(CONFIGS.get(network));

        // Set up wpa_supplicant.
        when(mWifiNative.addNetwork()).thenReturn(0);
        when(mWifiNative.setNetworkVariable(eq(network), anyString(), anyString()))
                .thenReturn(true);
        when(mWifiNative.setNetworkExtra(eq(network), anyString(),
                (Map<String, String>) anyObject())).thenReturn(true);
        when(mWifiNative.getNetworkVariable(network, WifiConfiguration.ssidVarName))
                .thenReturn(encodeConfigSSID(CONFIGS.get(network)));

        // Store a network configuration.
        mConfigStore.saveNetwork(CONFIGS.get(network), CONFIGS.get(network).creatorUid);

        // Verify that wpa_supplicant variables were written correctly for the network
        // configuration.
        final Map<String, String> metadata = new HashMap<String, String>();
        if (CONFIGS.get(network).FQDN != null) {
            metadata.put(WifiConfigStore.ID_STRING_KEY_FQDN, CONFIGS.get(network).FQDN);
        }
        metadata.put(WifiConfigStore.ID_STRING_KEY_CONFIG_KEY, CONFIGS.get(network).configKey());
        metadata.put(WifiConfigStore.ID_STRING_KEY_CREATOR_UID,
                Integer.toString(CONFIGS.get(network).creatorUid));
        verify(mWifiNative).setNetworkExtra(network, WifiConfigStore.ID_STRING_VAR_NAME,
                metadata);

        // Verify that no wpa_supplicant variables were read or written for any other network
        // configurations.
        verify(mWifiNative, never()).setNetworkExtra(intThat(not(network)), anyString(),
                (Map<String, String>) anyObject());
        verify(mWifiNative, never()).setNetworkVariable(intThat(not(network)), anyString(),
                anyString());
        verify(mWifiNative, never()).getNetworkVariable(intThat(not(network)), anyString());

        // Parse networkHistory.txt.
        assertNotNull(mNetworkHistory);
        final DataInputStream stream =
                new DataInputStream(new ByteArrayInputStream(mNetworkHistory));
        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();
        try {
            while (true) {
                final String[] tokens = stream.readUTF().split(":", 2);
                if (tokens.length == 2) {
                    keys.add(tokens[0].trim());
                    values.add(tokens[1].trim());
                }
            }
        } catch (EOFException e) {
            // Ignore. This is expected.
        }

        // Verify that a networkHistory.txt entry was written correctly for the network
        // configuration.
        assertTrue(keys.size() >= 3);
        assertEquals(WifiConfigStore.CONFIG_KEY, keys.get(0));
        assertEquals(CONFIGS.get(network).configKey(), values.get(0));
        final int creatorUidIndex = keys.indexOf(WifiConfigStore.CREATOR_UID_KEY);
        assertTrue(creatorUidIndex != -1);
        assertEquals(Integer.toString(CONFIGS.get(network).creatorUid),
                values.get(creatorUidIndex));
        final int sharedIndex = keys.indexOf(WifiConfigStore.SHARED_KEY);
        assertTrue(sharedIndex != -1);
        assertEquals(Boolean.toString(CONFIGS.get(network).shared), values.get(sharedIndex));

        // Verify that no networkHistory.txt entries were written for any other network
        // configurations.
        final int lastConfigIndex = keys.lastIndexOf(WifiConfigStore.CONFIG_KEY);
        assertEquals(0, lastConfigIndex);
    }

    /**
     * Verifies that saveNetwork() correctly stores a regular network configuration.
     */
    public void testSaveNetworkRegular() throws Exception {
        verifySaveNetwork(0);
    }

    /**
     * Verifies that saveNetwork() correctly stores a HotSpot 2.0 network configuration.
     */
    public void testSaveNetworkHotspot20() throws Exception {
        verifySaveNetwork(1);
    }

    /**
     * Verifies that saveNetwork() correctly stores a private network configuration.
     */
    public void testSaveNetworkPrivate() throws Exception {
        verifySaveNetwork(2);
    }

    /**
     * Verifies that loadConfiguredNetworks() correctly reads data from the wpa_supplicant, the
     * networkHistory.txt file and the MOManager, correlating the three sources based on the
     * configKey and the FQDN for HotSpot 2.0 networks.
     * TODO: Test all variables. Currently, only the following variables are tested:
     * - In the wpa_supplicant: "ssid", "id_str"
     * - In networkHistory.txt: "CONFIG", "CREATOR_UID_KEY", "SHARED"
     */
    public void testLoadConfiguredNetworks() throws Exception {
        // Set up list of network configurations returned by wpa_supplicant.
        final String header = "network id / ssid / bssid / flags";
        String networks = header;
        for (WifiConfiguration config : CONFIGS) {
            networks += "\n" + Integer.toString(config.networkId) + "\t" + config.SSID + "\tany";
        }
        when(mWifiNative.listNetworks(anyInt())).thenReturn(header);
        when(mWifiNative.listNetworks(-1)).thenReturn(networks);

        // Set up variables returned by wpa_supplicant for the individual network configurations.
        for (int i = 0; i < CONFIGS.size(); ++i) {
            when(mWifiNative.getNetworkVariable(i, WifiConfiguration.ssidVarName))
                .thenReturn(encodeConfigSSID(CONFIGS.get(i)));
        }
        // Legacy regular network configuration: No "id_str".
        when(mWifiNative.getNetworkExtra(0, WifiConfigStore.ID_STRING_VAR_NAME))
            .thenReturn(null);
        // Legacy Hotspot 2.0 network configuration: Quoted FQDN in "id_str".
        when(mWifiNative.getNetworkExtra(1, WifiConfigStore.ID_STRING_VAR_NAME))
            .thenReturn(null);
        when(mWifiNative.getNetworkVariable(1, WifiConfigStore.ID_STRING_VAR_NAME))
            .thenReturn('"' + CONFIGS.get(1).FQDN + '"');
        // Up-to-date configuration: Metadata in "id_str".
        final Map<String, String> metadata = new HashMap<String, String>();
        metadata.put(WifiConfigStore.ID_STRING_KEY_CONFIG_KEY, CONFIGS.get(2).configKey());
        metadata.put(WifiConfigStore.ID_STRING_KEY_CREATOR_UID,
                Integer.toString(CONFIGS.get(2).creatorUid));
        metadata.put(WifiConfigStore.ID_STRING_KEY_FQDN, CONFIGS.get(2).FQDN);
        when(mWifiNative.getNetworkExtra(2, WifiConfigStore.ID_STRING_VAR_NAME))
            .thenReturn(metadata);

        // Set up networkHistory.txt file.
        final File file = File.createTempFile("networkHistory.txt", null);
        file.deleteOnExit();
        Field wifiConfigStoreNetworkHistoryConfigFile =
                WifiConfigStore.class.getDeclaredField("networkHistoryConfigFile");
        wifiConfigStoreNetworkHistoryConfigFile.setAccessible(true);
        wifiConfigStoreNetworkHistoryConfigFile.set(null, file.getAbsolutePath());
        final DataOutputStream stream = new DataOutputStream(new FileOutputStream(file));
        for (WifiConfiguration config : CONFIGS) {
            stream.writeUTF(WifiConfigStore.CONFIG_KEY + ":  " + config.configKey() + '\n');
            stream.writeUTF(WifiConfigStore.CREATOR_UID_KEY + ":  "
                    + Integer.toString(config.creatorUid) + '\n');
            stream.writeUTF(WifiConfigStore.SHARED_KEY + ":  "
                    + Boolean.toString(config.shared) + '\n');
        }
        stream.close();

        // Set up list of home service providers returned by MOManager.
        final List<HomeSP> homeSPs = new ArrayList<HomeSP>();
        for (WifiConfiguration config : CONFIGS) {
            if (config.FQDN != null) {
                homeSPs.add(new HomeSP(null, config.FQDN, new HashSet<Long>(),
                        new HashSet<String>(), new HashSet<Long>(), new ArrayList<Long>(),
                        config.providerFriendlyName, null, new Credential(0, 0, null, false, null,
                        null), null, 0, null, null, null));
            }
        }
        when(mMOManager.loadAllSPs()).thenReturn(homeSPs);

        // Load network configurations.
        mConfigStore.loadConfiguredNetworks();

        // Verify that network configurations were loaded and correlated correctly across the three
        // sources.
        verifyNetworkConfigs(CONFIGS, mConfiguredNetworks.valuesForAllUsers());
    }

    /**
     * Verifies that handleUserSwitch() removes ephemeral network configurations, disables network
     * configurations that should no longer be visible and enables network configurations that
     * should become visible.
     */
    private void verifyHandleUserSwitch(int oldUserId, int newUserId,
            boolean makeOneConfigEphemeral) throws Exception {
        addNetworks();
        switchUser(oldUserId);

        final WifiNative wifiNative = createNewWifiNativeMock();
        final Field lastSelectedConfigurationField =
                WifiConfigStore.class.getDeclaredField("lastSelectedConfiguration");
        lastSelectedConfigurationField.setAccessible(true);
        WifiConfiguration removedEphemeralConfig = null;
        final Set<WifiConfiguration> oldUserOnlyConfigs = new HashSet<>();
        final Set<WifiConfiguration> newUserOnlyConfigs = new HashSet<>();
        final Set<WifiConfiguration> neitherUserConfigs = new HashSet<>();
        final Collection<WifiConfiguration> oldConfigs = mConfiguredNetworks.valuesForAllUsers();
        int expectedNumberOfConfigs = oldConfigs.size();
        for (WifiConfiguration config : oldConfigs) {
            if (config.isVisibleToUser(oldUserId)) {
                config.status = WifiConfiguration.Status.ENABLED;
                if (config.isVisibleToUser(newUserId)) {
                    if (makeOneConfigEphemeral && removedEphemeralConfig == null) {
                        config.ephemeral = true;
                        lastSelectedConfigurationField.set(mConfigStore, config.configKey());
                        removedEphemeralConfig = config;
                    }
                } else {
                    oldUserOnlyConfigs.add(config);
                }
            } else {
                config.status = WifiConfiguration.Status.DISABLED;
                if (config.isVisibleToUser(newUserId)) {
                    newUserOnlyConfigs.add(config);
                } else {
                    neitherUserConfigs.add(config);
                }
            }
        }
        when(wifiNative.disableNetwork(anyInt())).thenReturn(true);

        switchUser(newUserId);
        if (makeOneConfigEphemeral) {
            // Verify that the ephemeral network configuration was removed.
            assertNotNull(removedEphemeralConfig);
            assertNull(mConfiguredNetworks.getForAllUsers(removedEphemeralConfig.networkId));
            assertNull(lastSelectedConfigurationField.get(mConfigStore));
            verify(wifiNative).removeNetwork(removedEphemeralConfig.networkId);
            --expectedNumberOfConfigs;
        } else {
            assertNull(removedEphemeralConfig);
        }

        // Verify that the other network configurations were revealed/hidden and enabled/disabled as
        // appropriate.
        final Collection<WifiConfiguration> newConfigs = mConfiguredNetworks.valuesForAllUsers();
        assertEquals(expectedNumberOfConfigs, newConfigs.size());
        for (WifiConfiguration config : newConfigs) {
            if (oldUserOnlyConfigs.contains(config)) {
                verify(wifiNative).disableNetwork(config.networkId);
                assertEquals(WifiConfiguration.Status.DISABLED, config.status);
            } else {
                verify(wifiNative, never()).disableNetwork(config.networkId);
                if (neitherUserConfigs.contains(config)) {
                    assertEquals(WifiConfiguration.Status.DISABLED, config.status);
                } else {
                    assertEquals(WifiConfiguration.Status.ENABLED, config.status);
                }
            }
        }
    }

    /**
     * Verifies that handleUserSwitch() behaves correctly when the user switch removes an ephemeral
     * network configuration and reveals a private network configuration.
     */
    public void testHandleUserSwitchWithEphemeral() throws Exception {
        verifyHandleUserSwitch(USER_IDS[2], USER_IDS[0], true);
    }

    /**
     * Verifies that handleUserSwitch() behaves correctly when the user switch hides a private
     * network configuration.
     */
    public void testHandleUserSwitchWithoutEphemeral() throws Exception {
        verifyHandleUserSwitch(USER_IDS[0], USER_IDS[2], false);
    }
}