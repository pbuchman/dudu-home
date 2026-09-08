package com.pbuchman.duduhome.gate.syu;



import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

public final class SyuModuleBinder {
    public static final String DESCRIPTOR = "com.syu.ipc.IRemoteModule";

    private static final int TRANSACTION_COMMAND = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TRANSACTION_REGISTER = IBinder.FIRST_CALL_TRANSACTION + 2;
    private static final int TRANSACTION_UNREGISTER = IBinder.FIRST_CALL_TRANSACTION + 3;

    private final IBinder binder;

    public SyuModuleBinder(IBinder binder) {
        this.binder = binder;
    }

    public void command(int commandCode, int[] ints, float[] floats, String[] strings)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(commandCode);
            data.writeIntArray(ints);
            data.writeFloatArray(floats);
            data.writeStringArray(strings);
            transactChecked(TRANSACTION_COMMAND, data, reply, "command");
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public void register(SyuModuleCallback callback, int updateCode, int updateNow)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeStrongBinder(callback);
            data.writeInt(updateCode);
            data.writeInt(updateNow);
            transactOnewayChecked(TRANSACTION_REGISTER, data, "register");
        } finally {
            data.recycle();
        }
    }

    public void unregister(SyuModuleCallback callback, int updateCode) throws RemoteException {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeStrongBinder(callback);
            data.writeInt(updateCode);
            transactOnewayChecked(TRANSACTION_UNREGISTER, data, "unregister");
        } finally {
            data.recycle();
        }
    }

    private void transactChecked(int transaction, Parcel data, Parcel reply, String operation)
            throws RemoteException {
        boolean handled = binder.transact(transaction, data, reply, 0);
        if (!handled) {
            throw new RemoteException("IRemoteModule did not handle " + operation);
        }
        reply.readException();
    }

    private void transactOnewayChecked(int transaction, Parcel data, String operation)
            throws RemoteException {
        boolean handled = binder.transact(transaction, data, null, IBinder.FLAG_ONEWAY);
        if (!handled) {
            throw new RemoteException("IRemoteModule did not handle " + operation);
        }
    }
}
