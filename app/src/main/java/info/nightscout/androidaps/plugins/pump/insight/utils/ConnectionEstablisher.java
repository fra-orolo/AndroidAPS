package info.nightscout.androidaps.plugins.pump.insight.utils;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.UUID;

public class ConnectionEstablisher extends Thread {

    private Callback callback;
    private boolean forPairing;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothDevice bluetoothDevice;
    private BluetoothSocket socket;
    private Context context;

    public ConnectionEstablisher(Callback callback, boolean forPairing, BluetoothAdapter bluetoothAdapter, BluetoothDevice bluetoothDevice, BluetoothSocket socket, Context context) {
        this.callback = callback;
        this.forPairing = forPairing;
        this.bluetoothAdapter = bluetoothAdapter;
        this.bluetoothDevice = bluetoothDevice;
        this.socket = socket;
        this.context = context;
    }

    public synchronized void startPairing() {
        String tag = ConnectionEstablisher.class.getSimpleName();
        try {
            if ( ! ensureAdapterEnabled()) {
                if (!isInterrupted()) callback.onConnectionFail(new RuntimeException("Bluetooth Adapter disabled"), 0);
                return;
            }
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
            if (bluetoothDevice.getBondState() != BluetoothDevice.BOND_NONE) {
                try {
                    Method removeBond = bluetoothDevice.getClass().getMethod("removeBond", (Class[]) null);
                    removeBond.invoke(bluetoothDevice, (Object[]) null);
                } catch (ReflectiveOperationException e) {
                    if (!isInterrupted()) callback.onConnectionFail(e, 0);
                    return;
                }
            }
            IntentFilter intentFilter = new IntentFilter("android.bluetooth.device.action.BOND_STATE_CHANGED");
            BondingBroadcastReceiver bondingReceiver = new BondingBroadcastReceiver();
            context.registerReceiver(bondingReceiver, intentFilter);
            bluetoothDevice.createBond();
        } catch (RuntimeException rte) {
            Log.e(tag, "Problem in pairing", rte);
            callback.onConnectionFail(rte, 0);
        }

    }

    @Override
    public void run() {
        if (! ensureAdapterEnabled() ) return;
        try {
            if (socket == null) {
                socket = bluetoothDevice.createInsecureRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"));
                callback.onSocketCreated(socket);
            }
        } catch (IOException e) {
            if (!isInterrupted()) callback.onConnectionFail(e, 0);
            return;
        }
        long connectionStart = System.currentTimeMillis();
        try {
            socket.connect();
            if (!isInterrupted()) callback.onConnectionSucceed();
        } catch (IOException e) {
            if (!isInterrupted()) callback.onConnectionFail(e, System.currentTimeMillis() - connectionStart);
        }
    }

    public void close(boolean closeSocket) {
        try {
            interrupt();
            if (closeSocket && socket != null && socket.isConnected()) socket.close();
        } catch (IOException ignored) {
        }
    }

    public interface Callback {
        void onSocketCreated(BluetoothSocket bluetoothSocket);

        void onConnectionSucceed();

        void onConnectionFail(Exception e, long duration);
    }

    public class BondingBroadcastReceiver extends BroadcastReceiver {
        private int attempts = 0;
        private int lastBondState = BluetoothDevice.BOND_NONE;

        @Override
        public void onReceive(Context context, Intent intent) {
            String tag = ConnectionEstablisher.class.getSimpleName();
            String action = intent.getAction();
            String desiredMac = bluetoothDevice.getAddress();
            if ("android.bluetooth.device.action.BOND_STATE_CHANGED".equals(action)) {
                BluetoothDevice device = (BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int newBondState = device.getBondState();
                if (newBondState == BluetoothDevice.BOND_BONDED) {
                    context.unregisterReceiver(this);
                    start();
                } else if (lastBondState == BluetoothDevice.BOND_NONE && newBondState == BluetoothDevice.BOND_BONDING) {
                    attempts++;
                } else if (lastBondState != BluetoothDevice.BOND_NONE && newBondState == BluetoothDevice.BOND_NONE) {
                    if (attempts < 3) {
                        try {
                            Thread.sleep(1000);
                            device.createBond();
                        } catch (InterruptedException iInt) {
                        }
                    } else {
                        String msg = "Give up bonding to " + desiredMac;
                        context.unregisterReceiver(this);
                        callback.onConnectionFail(new RuntimeException(), 0);
                    }
                }
                lastBondState = newBondState;
            }
        }
    }

    private boolean ensureAdapterEnabled() {
        try {
            if (! bluetoothAdapter.isEnabled() ) {
                bluetoothAdapter.enable();
                Thread.sleep(2000);
            }
        } catch (InterruptedException ignored) {
        }
        return bluetoothAdapter.isEnabled();
    }

}
