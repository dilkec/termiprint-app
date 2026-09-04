package com.termiprint.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Gestor nativo de conexión Bluetooth SPP (RFCOMM) para impresoras térmicas ESC/POS.
 */
public class BluetoothPrinter {

    // UUID estándar para Serial Port Profile (SPP) de impresoras térmicas
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket socket;
    private OutputStream outputStream;
    private boolean isConnected = false;
    private String connectedDeviceName = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface ConnectionCallback {
        void onConnected(String deviceName);
        void onDisconnected();
        void onError(String error);
    }

    public interface PrintProgressCallback {
        void onProgress(int percent);
        void onComplete();
        void onError(String error);
    }

    public BluetoothPrinter() {
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public boolean isBluetoothSupported() {
        return bluetoothAdapter != null;
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    public boolean isConnected() {
        return isConnected && socket != null && socket.isConnected();
    }

    public String getConnectedDeviceName() {
        return connectedDeviceName;
    }

    @SuppressLint("MissingPermission")
    public List<BluetoothDevice> getPairedPrinters() {
        List<BluetoothDevice> list = new ArrayList<>();
        if (bluetoothAdapter != null) {
            Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
            if (paired != null) {
                list.addAll(paired);
            }
        }
        return list;
    }

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device, ConnectionCallback callback) {
        new Thread(() -> {
            try {
                disconnect();

                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                }

                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                outputStream = socket.getOutputStream();
                isConnected = true;
                connectedDeviceName = device.getName() != null ? device.getName() : device.getAddress();

                mainHandler.post(() -> callback.onConnected(connectedDeviceName));
            } catch (Exception e) {
                disconnect();
                mainHandler.post(() -> callback.onError("Error al conectar: " + e.getMessage()));
            }
        }).start();
    }

    public void print(byte[] data, PrintProgressCallback callback) {
        if (!isConnected()) {
            callback.onError("La impresora no está conectada.");
            return;
        }

        new Thread(() -> {
            try {
                int total = data.length;
                int sent = 0;
                int chunkSize = 512; // Bloques de 512 bytes para no saturar la RAM de la impresora

                while (sent < total) {
                    int len = Math.min(chunkSize, total - sent);
                    outputStream.write(data, sent, len);
                    outputStream.flush();
                    sent += len;

                    final int percent = Math.round(((float) sent / total) * 100);
                    mainHandler.post(() -> callback.onProgress(percent));

                    Thread.sleep(15); // Pequeña pausa para vaciar buffer
                }

                mainHandler.post(callback::onComplete);
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Error al imprimir: " + e.getMessage()));
            }
        }).start();
    }

    public void disconnect() {
        try {
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }
            if (socket != null) {
                socket.close();
                socket = null;
            }
        } catch (IOException ignored) {}
        isConnected = false;
        connectedDeviceName = null;
    }
}
