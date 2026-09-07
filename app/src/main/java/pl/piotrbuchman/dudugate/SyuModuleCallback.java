package pl.piotrbuchman.dudugate;

import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

final class SyuModuleCallback extends Binder implements IInterface {
    static final String DESCRIPTOR = "com.syu.ipc.IModuleCallback";
    private static final int TRANSACTION_UPDATE = IBinder.FIRST_CALL_TRANSACTION;

    interface Listener {
        void onUpdate(int updateCode, int[] ints, float[] floats, String[] strings);
    }

    private final Handler eventHandler;
    private final Listener listener;
    private volatile boolean active = true;

    SyuModuleCallback(Handler eventHandler, Listener listener) {
        this.eventHandler = eventHandler;
        this.listener = listener;
        attachInterface(this, DESCRIPTOR);
    }

    void deactivate() {
        active = false;
    }

    @Override
    public IBinder asBinder() {
        return this;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            if (reply != null) {
                reply.writeString(DESCRIPTOR);
            }
            return true;
        }

        if (code == TRANSACTION_UPDATE) {
            data.enforceInterface(DESCRIPTOR);
            int updateCode = data.readInt();
            int[] ints = data.createIntArray();
            float[] floats = data.createFloatArray();
            String[] strings = data.createStringArray();

            if (active) {
                eventHandler.post(() -> {
                    if (active) {
                        listener.onUpdate(updateCode, ints, floats, strings);
                    }
                });
            }

            if (reply != null) {
                reply.writeNoException();
            }
            return true;
        }

        return super.onTransact(code, data, reply, flags);
    }
}
