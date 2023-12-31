package com.android.bluetooth.btservice;

import static android.Manifest.permission.BLUETOOTH_CONNECT;

import static org.mockito.Mockito.*;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAssignedNumbers;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSinkAudioPolicy;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Message;
import android.os.TestLooperManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.Utils;
import com.android.bluetooth.bas.BatteryService;
import com.android.bluetooth.btservice.RemoteDevices.DeviceProperties;
import com.android.bluetooth.hfp.HeadsetHalConstants;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class RemoteDevicesTest {
    private static final String TEST_BT_ADDR_1 = "00:11:22:33:44:55";

    private ArgumentCaptor<Intent> mIntentArgument = ArgumentCaptor.forClass(Intent.class);
    private ArgumentCaptor<String> mStringArgument = ArgumentCaptor.forClass(String.class);
    private BluetoothDevice mDevice1;
    private RemoteDevices mRemoteDevices;
    private HandlerThread mHandlerThread;
    private TestLooperManager mTestLooperManager;

    private Context mTargetContext;
    private BluetoothManager mBluetoothManager;

    @Mock private AdapterService mAdapterService;

    @Before
    public void setUp() {
        mTargetContext = InstrumentationRegistry.getTargetContext();

        MockitoAnnotations.initMocks(this);
        mDevice1 = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(TEST_BT_ADDR_1);
        mHandlerThread = new HandlerThread("RemoteDevicesTestHandlerThread");
        mHandlerThread.start();
        mTestLooperManager = InstrumentationRegistry.getInstrumentation()
                .acquireLooperManager(mHandlerThread.getLooper());

        mBluetoothManager = mTargetContext.getSystemService(BluetoothManager.class);
        when(mAdapterService.getSystemService(Context.BLUETOOTH_SERVICE))
                .thenReturn(mBluetoothManager);
        when(mAdapterService.getSystemServiceName(BluetoothManager.class))
                .thenReturn(Context.BLUETOOTH_SERVICE);

        mRemoteDevices = new RemoteDevices(mAdapterService, mHandlerThread.getLooper());
        verify(mAdapterService, times(1)).getSystemService(Context.BLUETOOTH_SERVICE);
        verify(mAdapterService, times(1)).getSystemService(BluetoothManager.class);
    }

    @After
    public void tearDown() {
        mTestLooperManager.release();
        mHandlerThread.quit();
    }

    @Test
    public void testSendUuidIntent() {
        doNothing().when(mAdapterService).sendUuidsInternal(any(), any());

        // Verify that a handler message is sent by the method call
        mRemoteDevices.updateUuids(mDevice1);
        Message msg = mTestLooperManager.next();
        Assert.assertNotNull(msg);

        // Verify that executing that message results in a direct call and broadcast intent
        mTestLooperManager.execute(msg);
        verify(mAdapterService).sendUuidsInternal(any(), any());
        verify(mAdapterService).sendBroadcast(any(), anyString(), any());
        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testUpdateBatteryLevel_normalSequence() {
        int batteryLevel = 10;

        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService).sendBroadcast(mIntentArgument.capture(), mStringArgument.capture(),
                any(Bundle.class));
        verifyBatteryLevelChangedIntent(mDevice1, batteryLevel, mIntentArgument);
        Assert.assertEquals(BLUETOOTH_CONNECT, mStringArgument.getValue());

        // Verify that user can get battery level after the update
        Assert.assertNotNull(mRemoteDevices.getDeviceProperties(mDevice1));
        Assert.assertEquals(mRemoteDevices.getDeviceProperties(mDevice1).getBatteryLevel(),
                batteryLevel);

        // Verify that update same battery level for the same device does not trigger intent
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService).sendBroadcast(any(), anyString(), any());

        // Verify that updating battery level to different value triggers the intent again
        batteryLevel = 15;
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService, times(2)).sendBroadcast(mIntentArgument.capture(),
                mStringArgument.capture(), any(Bundle.class));
        Assert.assertEquals(BLUETOOTH_CONNECT, mStringArgument.getValue());
        verifyBatteryLevelChangedIntent(mDevice1, batteryLevel, mIntentArgument);

        // Verify that user can get battery level after the update
        Assert.assertEquals(mRemoteDevices.getDeviceProperties(mDevice1).getBatteryLevel(),
                batteryLevel);

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testUpdateBatteryLevel_errorNegativeValue() {
        int batteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;

        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that updating with invalid battery level does not trigger the intent
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService, never()).sendBroadcast(any(), anyString(), any());

        // Verify that device property stays null after invalid update
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testUpdateBatteryLevel_errorTooLargeValue() {
        int batteryLevel = 101;

        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that updating invalid battery level does not trigger the intent
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService, never()).sendBroadcast(any(), anyString(), any());

        // Verify that device property stays null after invalid update
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testResetBatteryLevel_testResetBeforeUpdate() {
        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that resetting battery level keeps device property null
        mRemoteDevices.resetBatteryLevel(mDevice1);
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testResetBatteryLevel_testResetAfterUpdate() {
        int batteryLevel = 10;

        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService).sendBroadcast(mIntentArgument.capture(), mStringArgument.capture(),
                any(Bundle.class));
        verifyBatteryLevelChangedIntent(mDevice1, batteryLevel, mIntentArgument);
        Assert.assertEquals(BLUETOOTH_CONNECT, mStringArgument.getValue());

        // Verify that user can get battery level after the update
        Assert.assertNotNull(mRemoteDevices.getDeviceProperties(mDevice1));
        Assert.assertEquals(mRemoteDevices.getDeviceProperties(mDevice1).getBatteryLevel(),
                batteryLevel);

        // Verify that resetting battery level changes it back to BluetoothDevice
        // .BATTERY_LEVEL_UNKNOWN
        mRemoteDevices.resetBatteryLevel(mDevice1);
        // Verify BATTERY_LEVEL_CHANGED intent is sent after first reset
        verify(mAdapterService, times(2)).sendBroadcast(mIntentArgument.capture(),
                mStringArgument.capture(), any(Bundle.class));
        verifyBatteryLevelChangedIntent(mDevice1, BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                mIntentArgument);
        Assert.assertEquals(BLUETOOTH_CONNECT, mStringArgument.getValue());
        // Verify value is reset in properties
        Assert.assertNotNull(mRemoteDevices.getDeviceProperties(mDevice1));
        Assert.assertEquals(mRemoteDevices.getDeviceProperties(mDevice1).getBatteryLevel(),
                BluetoothDevice.BATTERY_LEVEL_UNKNOWN);

        // Verify no intent is sent after second reset
        mRemoteDevices.resetBatteryLevel(mDevice1);
        verify(mAdapterService, times(2)).sendBroadcast(any(), anyString(),
                any());

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent again
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService, times(3)).sendBroadcast(mIntentArgument.capture(),
                mStringArgument.capture(), any(Bundle.class));
        verifyBatteryLevelChangedIntent(mDevice1, batteryLevel, mIntentArgument);
        Assert.assertEquals(BLUETOOTH_CONNECT, mStringArgument.getValue());

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testResetBatteryLevelOnHeadsetStateChange() {
        int batteryLevel = 10;

        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService).sendBroadcast(mIntentArgument.capture(), mStringArgument.capture(),
                any(Bundle.class));
        verifyBatteryLevelChangedIntent(mDevice1, batteryLevel, mIntentArgument);
        Assert.assertEquals(BLUETOOTH_CONNECT, mStringArgument.getValue());

        // Verify that user can get battery level after the update
        Assert.assertNotNull(mRemoteDevices.getDeviceProperties(mDevice1));
        Assert.assertEquals(mRemoteDevices.getDeviceProperties(mDevice1).getBatteryLevel(),
                batteryLevel);

        // Verify that resetting battery level changes it back to BluetoothDevice
        // .BATTERY_LEVEL_UNKNOWN
        mRemoteDevices.onHeadsetConnectionStateChanged(
                getHeadsetConnectionStateChangedIntent(mDevice1,
                        BluetoothProfile.STATE_DISCONNECTING, BluetoothProfile.STATE_DISCONNECTED));
        // Verify BATTERY_LEVEL_CHANGED intent is sent after first reset
        verify(mAdapterService, times(2)).sendBroadcast(mIntentArgument.capture(),
                mStringArgument.capture(), any(Bundle.class));
        verifyBatteryLevelChangedIntent(mDevice1, BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                mIntentArgument);
        Assert.assertEquals(BLUETOOTH_CONNECT, mStringArgument.getValue());
        // Verify value is reset in properties
        Assert.assertNotNull(mRemoteDevices.getDeviceProperties(mDevice1));
        Assert.assertEquals(mRemoteDevices.getDeviceProperties(mDevice1).getBatteryLevel(),
                BluetoothDevice.BATTERY_LEVEL_UNKNOWN);

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent again
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService, times(3)).sendBroadcast(mIntentArgument.capture(),
                mStringArgument.capture(), any(Bundle.class));
        verifyBatteryLevelChangedIntent(mDevice1, batteryLevel, mIntentArgument);
        Assert.assertEquals(BLUETOOTH_CONNECT, mStringArgument.getValue());

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testOnHeadsetStateChangeWithBatteryService_NotResetBatteryLevel() {
        int batteryLevel = 10;

        BatteryService oldBatteryService = setBatteryServiceForTesting(mDevice1);
        Assert.assertTrue(mRemoteDevices.hasBatteryService(mDevice1));

        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService).sendBroadcast(mIntentArgument.capture(), mStringArgument.capture(),
                any(Bundle.class));
        verifyBatteryLevelChangedIntent(mDevice1, batteryLevel, mIntentArgument);
        Assert.assertEquals(BLUETOOTH_CONNECT, mStringArgument.getValue());

        // Verify that user can get battery level after the update
        Assert.assertNotNull(mRemoteDevices.getDeviceProperties(mDevice1));
        Assert.assertEquals(mRemoteDevices.getDeviceProperties(mDevice1).getBatteryLevel(),
                batteryLevel);

        // Verify that battery level is not reset
        mRemoteDevices.onHeadsetConnectionStateChanged(
                getHeadsetConnectionStateChangedIntent(mDevice1,
                        BluetoothProfile.STATE_DISCONNECTING, BluetoothProfile.STATE_DISCONNECTED));

        Assert.assertNotNull(mRemoteDevices.getDeviceProperties(mDevice1));
        Assert.assertEquals(batteryLevel,
                mRemoteDevices.getDeviceProperties(mDevice1).getBatteryLevel());

        // Recover the previous battery service if exists
        clearBatteryServiceForTesting(oldBatteryService);

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    @Ignore("b/266128644")
    public void testResetBatteryLevel_testAclStateChangeCallback() {
        int batteryLevel = 10;

        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService).sendBroadcast(mIntentArgument.capture(), mStringArgument.capture(),
                any(Bundle.class));
        verifyBatteryLevelChangedIntent(mDevice1, batteryLevel, mIntentArgument);
        Assert.assertEquals(BLUETOOTH_CONNECT, mStringArgument.getValue());

        // Verify that user can get battery level after the update
        Assert.assertNotNull(mRemoteDevices.getDeviceProperties(mDevice1));
        Assert.assertEquals(batteryLevel,
                mRemoteDevices.getDeviceProperties(mDevice1).getBatteryLevel());

        // Verify that when device is completely disconnected, RemoteDevices reset battery level to
        // BluetoothDevice.BATTERY_LEVEL_UNKNOWN
        when(mAdapterService.getState()).thenReturn(BluetoothAdapter.STATE_ON);
        mRemoteDevices.aclStateChangeCallback(0, Utils.getByteAddress(mDevice1),
                AbstractionLayer.BT_ACL_STATE_DISCONNECTED, 2, 19,
                BluetoothDevice.ERROR); // HCI code 19 remote terminated
        // Verify ACTION_ACL_DISCONNECTED and BATTERY_LEVEL_CHANGED intent are sent
        verify(mAdapterService, times(3)).sendBroadcast(mIntentArgument.capture(),
                mStringArgument.capture(), any(Bundle.class));
        verify(mAdapterService, times(2)).obfuscateAddress(mDevice1);
        verifyBatteryLevelChangedIntent(mDevice1, BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                mIntentArgument.getAllValues().get(mIntentArgument.getAllValues().size() - 2));
        Assert.assertEquals(BLUETOOTH_CONNECT,
                mStringArgument.getAllValues().get(mStringArgument.getAllValues().size() - 2));
        Assert.assertEquals(BluetoothDevice.ACTION_ACL_DISCONNECTED,
                mIntentArgument.getValue().getAction());
        Assert.assertEquals(BLUETOOTH_CONNECT, mStringArgument.getValue());
        // Verify value is reset in properties
        Assert.assertNotNull(mRemoteDevices.getDeviceProperties(mDevice1));
        Assert.assertEquals(BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                mRemoteDevices.getDeviceProperties(mDevice1).getBatteryLevel());

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent again
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService, times(4)).sendBroadcast(mIntentArgument.capture(),
                mStringArgument.capture(), any(Bundle.class));
        verifyBatteryLevelChangedIntent(mDevice1, batteryLevel, mIntentArgument);
        Assert.assertEquals(BLUETOOTH_CONNECT, mStringArgument.getValue());
    }

    @Test
    public void testHfIndicatorParser_testCorrectValue() {
        int batteryLevel = 10;

        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that ACTION_HF_INDICATORS_VALUE_CHANGED intent updates battery level
        mRemoteDevices.onHfIndicatorValueChanged(getHfIndicatorIntent(mDevice1, batteryLevel,
                HeadsetHalConstants.HF_INDICATOR_BATTERY_LEVEL_STATUS));
        verify(mAdapterService).sendBroadcast(mIntentArgument.capture(), mStringArgument.capture(),
                any(Bundle.class));
        verifyBatteryLevelChangedIntent(mDevice1, batteryLevel, mIntentArgument);
        Assert.assertEquals(BLUETOOTH_CONNECT, mStringArgument.getValue());
    }

    @Test
    public void testHfIndicatorParser_testWrongIndicatorId() {
        int batteryLevel = 10;

        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that ACTION_HF_INDICATORS_VALUE_CHANGED intent updates battery level
        mRemoteDevices.onHfIndicatorValueChanged(getHfIndicatorIntent(mDevice1, batteryLevel, 3));
        verify(mAdapterService, never()).sendBroadcast(any(), anyString());
        // Verify that device property is still null after invalid update
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));
    }

    @Test
    public void testOnVendorSpecificHeadsetEvent_testCorrectPlantronicsXEvent() {
        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that correct ACTION_VENDOR_SPECIFIC_HEADSET_EVENT updates battery level
        mRemoteDevices.onVendorSpecificHeadsetEvent(getVendorSpecificHeadsetEventIntent(
                BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_XEVENT,
                BluetoothAssignedNumbers.PLANTRONICS, BluetoothHeadset.AT_CMD_TYPE_SET,
                getXEventArray(3, 8), mDevice1));
        verify(mAdapterService).sendBroadcast(mIntentArgument.capture(), mStringArgument.capture(),
                any(Bundle.class));
        verifyBatteryLevelChangedIntent(mDevice1, 42, mIntentArgument);
        Assert.assertEquals(BLUETOOTH_CONNECT, mStringArgument.getValue());
    }

    @Test
    public void testOnVendorSpecificHeadsetEvent_testCorrectAppleBatteryVsc() {
        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that correct ACTION_VENDOR_SPECIFIC_HEADSET_EVENT updates battery level
        mRemoteDevices.onVendorSpecificHeadsetEvent(getVendorSpecificHeadsetEventIntent(
                BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV,
                BluetoothAssignedNumbers.APPLE, BluetoothHeadset.AT_CMD_TYPE_SET, new Object[]{
                        3,
                        BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL,
                        5,
                        2,
                        1,
                        3,
                        10
                }, mDevice1));
        verify(mAdapterService).sendBroadcast(mIntentArgument.capture(), mStringArgument.capture(),
                any(Bundle.class));
        verifyBatteryLevelChangedIntent(mDevice1, 60, mIntentArgument);
        Assert.assertEquals(BLUETOOTH_CONNECT, mStringArgument.getValue());
    }

    @Test
    public void testGetBatteryLevelFromXEventVsc() {
        Assert.assertEquals(42, RemoteDevices.getBatteryLevelFromXEventVsc(getXEventArray(3, 8)));
        Assert.assertEquals(100,
                RemoteDevices.getBatteryLevelFromXEventVsc(getXEventArray(10, 11)));
        Assert.assertEquals(BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                RemoteDevices.getBatteryLevelFromXEventVsc(getXEventArray(1, 1)));
        Assert.assertEquals(BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                RemoteDevices.getBatteryLevelFromXEventVsc(getXEventArray(3, 1)));
        Assert.assertEquals(BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                RemoteDevices.getBatteryLevelFromXEventVsc(getXEventArray(-1, 1)));
        Assert.assertEquals(BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                RemoteDevices.getBatteryLevelFromXEventVsc(getXEventArray(-1, -1)));
    }

    @Test
    public void testGetBatteryLevelFromAppleBatteryVsc() {
        Assert.assertEquals(10, RemoteDevices.getBatteryLevelFromAppleBatteryVsc(new Object[]{
                1, BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL, 0
        }));
        Assert.assertEquals(100, RemoteDevices.getBatteryLevelFromAppleBatteryVsc(new Object[]{
                1, BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL, 9
        }));
        Assert.assertEquals(60, RemoteDevices.getBatteryLevelFromAppleBatteryVsc(new Object[]{
                3,
                BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL,
                5,
                2,
                1,
                3,
                10
        }));
        Assert.assertEquals(BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                RemoteDevices.getBatteryLevelFromAppleBatteryVsc(new Object[]{
                        3,
                        BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL,
                        5,
                        2,
                        1,
                        3
                }));
        Assert.assertEquals(BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                RemoteDevices.getBatteryLevelFromAppleBatteryVsc(new Object[]{
                        1,
                        BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL,
                        10
                }));
        Assert.assertEquals(BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                RemoteDevices.getBatteryLevelFromAppleBatteryVsc(new Object[]{
                        1,
                        BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL,
                        -1
                }));
        Assert.assertEquals(BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                RemoteDevices.getBatteryLevelFromAppleBatteryVsc(new Object[]{
                        1,
                        BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL,
                        "5"
                }));
        Assert.assertEquals(BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                RemoteDevices.getBatteryLevelFromAppleBatteryVsc(new Object[]{1, 35, 37}));
        Assert.assertEquals(BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                RemoteDevices.getBatteryLevelFromAppleBatteryVsc(
                        new Object[]{1, "WRONG", "WRONG"}));
    }

    @Test
    public void testResetBatteryLevelOnHeadsetClientStateChange() {
        int batteryLevel = 10;

        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService).sendBroadcast(mIntentArgument.capture(), mStringArgument.capture(),
                any(Bundle.class));
        verifyBatteryLevelChangedIntent(mDevice1, batteryLevel, mIntentArgument);
        Assert.assertEquals(BLUETOOTH_CONNECT, mStringArgument.getValue());

        // Verify that user can get battery level after the update
        Assert.assertNotNull(mRemoteDevices.getDeviceProperties(mDevice1));
        Assert.assertEquals(mRemoteDevices.getDeviceProperties(mDevice1).getBatteryLevel(),
                batteryLevel);

        // Verify that resetting battery level changes it back to BluetoothDevice
        // .BATTERY_LEVEL_UNKNOWN
        mRemoteDevices.onHeadsetClientConnectionStateChanged(
                getHeadsetClientConnectionStateChangedIntent(mDevice1,
                        BluetoothProfile.STATE_DISCONNECTING, BluetoothProfile.STATE_DISCONNECTED));

        // Verify BATTERY_LEVEL_CHANGED intent is sent after first reset
        verify(mAdapterService, times(2)).sendBroadcast(mIntentArgument.capture(),
                mStringArgument.capture(), any(Bundle.class));
        verifyBatteryLevelChangedIntent(mDevice1, BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                mIntentArgument);
        Assert.assertEquals(BLUETOOTH_CONNECT, mStringArgument.getValue());

        // Verify value is reset in properties
        Assert.assertNotNull(mRemoteDevices.getDeviceProperties(mDevice1));
        Assert.assertEquals(mRemoteDevices.getDeviceProperties(mDevice1).getBatteryLevel(),
                BluetoothDevice.BATTERY_LEVEL_UNKNOWN);

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent again
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService, times(3)).sendBroadcast(mIntentArgument.capture(),
                mStringArgument.capture(), any(Bundle.class));
        verifyBatteryLevelChangedIntent(mDevice1, batteryLevel, mIntentArgument);
        Assert.assertEquals(BLUETOOTH_CONNECT, mStringArgument.getValue());

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testHeadsetClientDisconnectedWithBatteryService_NotResetBatteryLevel() {
        int batteryLevel = 10;

        BatteryService oldBatteryService = setBatteryServiceForTesting(mDevice1);
        Assert.assertTrue(mRemoteDevices.hasBatteryService(mDevice1));

        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService).sendBroadcast(mIntentArgument.capture(), mStringArgument.capture(),
                any(Bundle.class));
        verifyBatteryLevelChangedIntent(mDevice1, batteryLevel, mIntentArgument);
        Assert.assertEquals(BLUETOOTH_CONNECT, mStringArgument.getValue());

        // Verify that user can get battery level after the update
        Assert.assertNotNull(mRemoteDevices.getDeviceProperties(mDevice1));
        Assert.assertEquals(mRemoteDevices.getDeviceProperties(mDevice1).getBatteryLevel(),
                batteryLevel);

        // Verify that battery level is not reset.
        mRemoteDevices.onHeadsetClientConnectionStateChanged(
                getHeadsetClientConnectionStateChangedIntent(mDevice1,
                        BluetoothProfile.STATE_DISCONNECTING, BluetoothProfile.STATE_DISCONNECTED));

        Assert.assertNotNull(mRemoteDevices.getDeviceProperties(mDevice1));
        Assert.assertEquals(batteryLevel,
                mRemoteDevices.getDeviceProperties(mDevice1).getBatteryLevel());

        clearBatteryServiceForTesting(oldBatteryService);

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testAGIndicatorParser_testCorrectValue() {
        int batteryLevel = 3;

        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that ACTION_AG_EVENT intent updates battery level
        mRemoteDevices.onAgIndicatorValueChanged(
                getAgIndicatorIntent(mDevice1, null, null, null, new Integer(batteryLevel), null));
        verify(mAdapterService).sendBroadcast(mIntentArgument.capture(), mStringArgument.capture(),
                any(Bundle.class));
        verifyBatteryLevelChangedIntent(mDevice1,
                RemoteDevices.batteryChargeIndicatorToPercentge(batteryLevel), mIntentArgument);
        Assert.assertEquals(BLUETOOTH_CONNECT, mStringArgument.getValue());
    }

    @Test
    public void testAgIndicatorParser_testWrongIndicatorId() {
        int batteryLevel = 10;

        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that ACTION_AG_EVENT intent updates battery level
        mRemoteDevices.onAgIndicatorValueChanged(
                getAgIndicatorIntent(mDevice1, new Integer(1), null, null, null, null));
        verify(mAdapterService, never()).sendBroadcast(any(), anyString());
        // Verify that device property is still null after invalid update
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));
    }

    @Test
    public void testSetgetHfAudioPolicyForRemoteAg() {
        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        mRemoteDevices.addDeviceProperties(Utils.getBytesFromAddress(TEST_BT_ADDR_1));

        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(mDevice1);
        BluetoothSinkAudioPolicy policies = new BluetoothSinkAudioPolicy.Builder()
                .setCallEstablishPolicy(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                .setActiveDevicePolicyAfterConnection(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                .setInBandRingtonePolicy(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                .build();
        deviceProp.setHfAudioPolicyForRemoteAg(policies);

        // Verify that the audio policy properties are set and get propperly
        Assert.assertEquals(policies, mRemoteDevices.getDeviceProperties(mDevice1)
                .getHfAudioPolicyForRemoteAg());
    }

    private static void verifyBatteryLevelChangedIntent(BluetoothDevice device, int batteryLevel,
            ArgumentCaptor<Intent> intentArgument) {
        verifyBatteryLevelChangedIntent(device, batteryLevel, intentArgument.getValue());
    }

    private static void verifyBatteryLevelChangedIntent(BluetoothDevice device, int batteryLevel,
            Intent intent) {
        Assert.assertEquals(BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED, intent.getAction());
        Assert.assertEquals(device, intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
        Assert.assertEquals(batteryLevel,
                intent.getIntExtra(BluetoothDevice.EXTRA_BATTERY_LEVEL, -15));
        Assert.assertEquals(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                        | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND, intent.getFlags());
    }

    private static Intent getHeadsetConnectionStateChangedIntent(BluetoothDevice device,
            int oldState, int newState) {
        Intent intent = new Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, oldState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
        return intent;
    }

    private static Intent getHfIndicatorIntent(BluetoothDevice device, int batteryLevel,
            int indicatorId) {
        Intent intent = new Intent(BluetoothHeadset.ACTION_HF_INDICATORS_VALUE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothHeadset.EXTRA_HF_INDICATORS_IND_ID, indicatorId);
        intent.putExtra(BluetoothHeadset.EXTRA_HF_INDICATORS_IND_VALUE, batteryLevel);
        return intent;
    }

    private static Intent getVendorSpecificHeadsetEventIntent(String command, int companyId,
            int commandType, Object[] arguments, BluetoothDevice device) {
        Intent intent = new Intent(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT);
        intent.putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD, command);
        intent.putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE, commandType);
        // assert: all elements of args are Serializable
        intent.putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS, arguments);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.addCategory(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY + "."
                + Integer.toString(companyId));
        return intent;
    }

    private static Object[] getXEventArray(int batteryLevel, int numLevels) {
        ArrayList<Object> list = new ArrayList<>();
        list.add(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_XEVENT_BATTERY_LEVEL);
        list.add(batteryLevel);
        list.add(numLevels);
        list.add(0);
        list.add(0);
        return list.toArray();
    }

    private static Intent getHeadsetClientConnectionStateChangedIntent(BluetoothDevice device,
            int oldState, int newState) {
        Intent intent = new Intent(BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, oldState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
        return intent;
    }

    private static Intent getAgIndicatorIntent(BluetoothDevice device, Integer networkStatus,
            Integer networkStrength, Integer roaming, Integer batteryLevel, String operatorName) {
        Intent intent = new Intent(BluetoothHeadsetClient.ACTION_AG_EVENT);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);

        if (networkStatus != null) {
            intent.putExtra(BluetoothHeadsetClient.EXTRA_NETWORK_STATUS, networkStatus.intValue());
        }
        if (networkStrength != null) {
            intent.putExtra(BluetoothHeadsetClient.EXTRA_NETWORK_SIGNAL_STRENGTH,
                    networkStrength.intValue());
        }
        if (roaming != null) {
            intent.putExtra(BluetoothHeadsetClient.EXTRA_NETWORK_ROAMING, roaming.intValue());
        }
        if (batteryLevel != null) {
            intent.putExtra(BluetoothHeadsetClient.EXTRA_BATTERY_LEVEL, batteryLevel.intValue());
        }
        if (operatorName != null) {
            intent.putExtra(BluetoothHeadsetClient.EXTRA_OPERATOR_NAME, operatorName);
        }

        return intent;
    }

    private static BatteryService setBatteryServiceForTesting(BluetoothDevice device) {
        BatteryService newService = mock(BatteryService.class);
        when(newService.getConnectionState(device))
                .thenReturn(BluetoothProfile.STATE_CONNECTED);
        when(newService.isAvailable()).thenReturn(true);

        BatteryService oldService = BatteryService.getBatteryService();
        BatteryService.setBatteryService(newService);

        return oldService;
    }

    private static void clearBatteryServiceForTesting(BatteryService service) {
        BatteryService.setBatteryService(service);
    }
}
