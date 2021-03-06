package com.example.beam.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.example.beam.BeamProfile;
import com.example.beam.MainActivity;
import com.example.beam.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.nio.charset.StandardCharsets;

public class PeripheralService extends Service {
    private static final long ADVERTISE_PERIOD = 300000;
    private static final int SERVICE_NOTIFICATION_ID = 1;

    private boolean isScanning = false;

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;

    private BluetoothGattServer bluetoothGattServer;
    private BluetoothGattServerCallback bluetoothGattServerCallback;

    private BluetoothLeAdvertiser bluetoothLeAdvertiser;
    private AdvertiseCallback advertiseCallback;

    private DatabaseReference mDatabase;
    private FirebaseUser currentUser;

    private boolean serviceStarted;
    private boolean hasConnected;

    private Handler handler = new Handler();
    private String attendanceToken;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (serviceStarted) {
            return START_STICKY;
        }
        serviceStarted = true;

        String moduleId = intent.getStringExtra("moduleId");
        String sessionId = intent.getStringExtra("sessionId");
        attendanceToken = BeamProfile.createAttendanceToken(moduleId, sessionId);

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new NotificationCompat.Builder(this, MainActivity.NOTIF_CHANNEL_SERVICE_ID)
                    .setContentTitle("Advertising tokens for " + moduleId)
                    .setContentText("Sending out tokens to other devices...")
                    .setContentIntent(pendingIntent)
                    .setSmallIcon(R.mipmap.ic_launcher_round)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(false)
                    .build();
        }
        else {
            notification = new NotificationCompat.Builder(this)
                    .setContentTitle("Advertising tokens for " + moduleId)
                    .setContentText("Sending out tokens to other devices...")
                    .setContentIntent(pendingIntent)
                    .setSmallIcon(R.mipmap.ic_launcher_round)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(false)
                    .build();
        }
        startForeground(SERVICE_NOTIFICATION_ID, notification);

        if (mDatabase == null) {
            Toast.makeText(getApplicationContext(), "No DATABASE", Toast.LENGTH_SHORT).show();
        }

        openGattServer();

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        serviceStarted = false;

        mDatabase = FirebaseDatabase.getInstance().getReference();
        currentUser = FirebaseAuth.getInstance().getCurrentUser();

        bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
    }

    @Override
    public void onDestroy() {
        stopAdvertising();
        if (bluetoothGattServer != null) {
            bluetoothGattServer.close();
        }
        super.onDestroy();
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void openGattServer() {
        if (!bluetoothAdapter.isMultipleAdvertisementSupported()) {
            Toast.makeText(getApplicationContext(), "Multiple advertising not supported", Toast.LENGTH_SHORT).show();
        }

        initialiseServer();
        startAdvertising();
    }

    private void initialiseServer() {
        Toast.makeText(this, "Opening server", Toast.LENGTH_SHORT).show();

        initialiseGattServerCallback();
        bluetoothGattServer = bluetoothManager.openGattServer(this, bluetoothGattServerCallback);
        BluetoothGattService bluetoothGattService = BeamProfile.getBeamService();
        bluetoothGattService.getCharacteristic(BeamProfile.CHARACTERISTIC_TOKEN_UUID).setValue(attendanceToken);
        bluetoothGattServer.addService(bluetoothGattService);
    }

    private void initialiseGattServerCallback() {
        bluetoothGattServerCallback = new BluetoothGattServerCallback() {
            @Override
            public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
                super.onConnectionStateChange(device, status, newState);
                switch(newState) {
                    case BluetoothProfile.STATE_CONNECTED:
                    case BluetoothProfile.STATE_CONNECTING:
                        hasConnected = true;
                        stopAdvertising();
                        break;
                    case BluetoothProfile.STATE_DISCONNECTED:
                    case BluetoothProfile.STATE_DISCONNECTING:
                        startAdvertising();
                        break;
                    default:
                }
            }

            @Override
            public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
                if (characteristic.getUuid().equals(BeamProfile.CHARACTERISTIC_TOKEN_UUID)) {
                    bluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.getStringValue(offset).getBytes(StandardCharsets.UTF_8));
                }
            }
        };
    }

private void startAdvertising() {
    bluetoothLeAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();

    AdvertiseSettings settings = new AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .build();

    AdvertiseData advertiseData = new AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(new ParcelUuid(BeamProfile.SERVICE_UUID))
            .build();

    AdvertiseData scanResponseData = new AdvertiseData.Builder()
            .addServiceUuid(new ParcelUuid(BeamProfile.SERVICE_UUID))
            .setIncludeTxPowerLevel(true)
            .build();

    bluetoothLeAdvertiser.startAdvertising(settings, advertiseData, scanResponseData, advertiseCallback);
    // Stops advertising if there is no connection over the past ADVERTISE_PERIOD
    handler.postDelayed(runnableStartAdvertising,ADVERTISE_PERIOD);
}

    private void stopAdvertising() {
        bluetoothLeAdvertiser.stopAdvertising(advertiseCallback);
        Toast.makeText(this, "Stopped Advertising", Toast.LENGTH_SHORT).show();
    }
}
