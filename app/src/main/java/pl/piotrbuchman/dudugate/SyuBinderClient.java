package pl.piotrbuchman.dudugate;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

final class SyuBinderClient {
    static final String TAG = "DuduGate";

    private static final String MS_PACKAGE = "com.syu.ms";
    private static final String TOOLKIT_ACTION = "com.syu.ms.toolkit";
    private static final String TOOLKIT_COMPONENT = "app.ToolkitService";
    private static final String MODULE_ACTION = "com.syu.ms.bt";
    private static final String MODULE_COMPONENT = "app.ModuleService";
    private static final String TOOLKIT_DESCRIPTOR = "com.syu.ipc.IRemoteToolkit";
    private static final int TRANSACTION_GET_REMOTE_MODULE = IBinder.FIRST_CALL_TRANSACTION;
    private static final int BLUETOOTH_MODULE_ID = 2;

    interface Listener {
        void onModuleReady(SyuModuleBinder module, String path);

        void onUnavailable(String detail);

        void onConnectionLost(String detail);
    }

    private enum Path {
        TOOLKIT("ToolkitService", TOOLKIT_ACTION, TOOLKIT_COMPONENT),
        DIRECT_MODULE("ModuleService", MODULE_ACTION, MODULE_COMPONENT);

        final String label;
        final String action;
        final String component;

        Path(String label, String action, String component) {
            this.label = label;
            this.action = action;
            this.component = component;
        }
    }

    private final Context context;
    private final Handler worker;
    private final Listener listener;

    private int generation;
    private boolean closed;
    private boolean bound;
    private boolean moduleDelivered;
    private boolean lossReported;
    private Path currentPath;
    private ServiceConnection currentConnection;
    private Runnable bindTimeout;
    private IBinder serviceBinder;
    private IBinder moduleBinder;
    private IBinder.DeathRecipient deathRecipient;

    SyuBinderClient(Context context, Handler worker, Listener listener) {
        this.context = context.getApplicationContext();
        this.worker = worker;
        this.listener = listener;
    }

    void connect() {
        if (!isPackageInstalled()) {
            Log.e(TAG, "Package com.syu.ms is not installed or not visible");
            listener.onUnavailable("Brak pakietu com.syu.ms.");
            return;
        }
        attemptBind(Path.TOOLKIT);
    }

    void close() {
        closed = true;
        invalidateAndUnbind();
    }

    @SuppressWarnings("deprecation")
    private boolean isPackageInstalled() {
        try {
            context.getPackageManager().getPackageInfo(MS_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException exception) {
            return false;
        }
    }

    private void attemptBind(Path path) {
        if (closed) {
            return;
        }

        invalidateAndUnbind();
        int activeGeneration = ++generation;
        currentPath = path;
        moduleDelivered = false;
        lossReported = false;

        Intent intent = new Intent(path.action)
                .setComponent(new ComponentName(MS_PACKAGE, path.component));
        ServiceConnection connection = createConnection(activeGeneration, path);
        currentConnection = connection;

        Log.i(TAG, "Binding started via " + path.label);
        boolean started;
        try {
            started = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException exception) {
            started = false;
            Log.e(TAG, "Binding threw via " + path.label + ": "
                    + exception.getClass().getSimpleName());
        }

        bound = started;
        if (!started) {
            handlePathFailure(activeGeneration, path, "bindService zwrócił false.");
            return;
        }

        bindTimeout = () -> {
            if (isCurrent(activeGeneration)) {
                Log.e(TAG, "Binding timeout via " + path.label);
                handlePathFailure(activeGeneration, path, "Timeout wiązania " + path.label + ".");
            }
        };
        worker.postDelayed(bindTimeout, GateConfig.BIND_TIMEOUT_MS);
    }

    private ServiceConnection createConnection(int activeGeneration, Path path) {
        return new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                worker.post(() -> handleConnected(activeGeneration, path, service));
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                worker.post(() -> handleConnectionLost(
                        activeGeneration, "Service disconnected: " + path.label));
            }

            @Override
            public void onBindingDied(ComponentName name) {
                worker.post(() -> handleConnectionLost(
                        activeGeneration, "Binding died: " + path.label));
            }

            @Override
            public void onNullBinding(ComponentName name) {
                worker.post(() -> handlePathFailure(
                        activeGeneration, path, "Usługa zwróciła null Binder."));
            }
        };
    }

    private void handleConnected(int activeGeneration, Path path, IBinder service) {
        if (!isCurrent(activeGeneration)) {
            return;
        }
        removeBindTimeout();
        Log.i(TAG, "Binding completed via " + path.label);

        if (service == null) {
            handlePathFailure(activeGeneration, path, "Usługa nie przekazała Bindera.");
            return;
        }

        serviceBinder = service;
        deathRecipient = () -> worker.post(() -> handleConnectionLost(
                activeGeneration, "Binder died: " + path.label));

        try {
            serviceBinder.linkToDeath(deathRecipient, 0);
            moduleBinder = path == Path.TOOLKIT
                    ? getRemoteModule(serviceBinder, BLUETOOTH_MODULE_ID)
                    : serviceBinder;
            if (moduleBinder == null) {
                handlePathFailure(activeGeneration, path, "Moduł Bluetooth ma null Binder.");
                return;
            }
            if (moduleBinder != serviceBinder) {
                moduleBinder.linkToDeath(deathRecipient, 0);
            }
        } catch (RemoteException | RuntimeException exception) {
            handlePathFailure(activeGeneration, path,
                    "Nie można uzyskać modułu Bluetooth: "
                            + exception.getClass().getSimpleName());
            return;
        }

        moduleDelivered = true;
        Log.i(TAG, "IPC path selected: " + path.label);
        listener.onModuleReady(new SyuModuleBinder(moduleBinder), path.label);
    }

    private void handlePathFailure(int activeGeneration, Path path, String detail) {
        if (!isCurrent(activeGeneration)) {
            return;
        }
        Log.e(TAG, path.label + " unavailable: " + detail);
        if (path == Path.TOOLKIT && !moduleDelivered) {
            Log.i(TAG, "Trying sequential fallback to ModuleService");
            attemptBind(Path.DIRECT_MODULE);
            return;
        }

        invalidateAndUnbind();
        listener.onUnavailable(detail);
    }

    private void handleConnectionLost(int activeGeneration, String detail) {
        if (!isCurrent(activeGeneration) || lossReported) {
            return;
        }
        if (!moduleDelivered && currentPath != null) {
            handlePathFailure(activeGeneration, currentPath, detail);
            return;
        }

        lossReported = true;
        Log.e(TAG, detail);
        listener.onConnectionLost(detail);
    }

    private boolean isCurrent(int activeGeneration) {
        return !closed && activeGeneration == generation;
    }

    private void invalidateAndUnbind() {
        generation++;
        removeBindTimeout();

        if (deathRecipient != null) {
            unlinkDeath(serviceBinder, deathRecipient);
            if (moduleBinder != serviceBinder) {
                unlinkDeath(moduleBinder, deathRecipient);
            }
        }

        if (bound && currentConnection != null) {
            try {
                context.unbindService(currentConnection);
                Log.i(TAG, "Binding closed");
            } catch (IllegalArgumentException exception) {
                Log.w(TAG, "Binding was already closed");
            }
        }

        bound = false;
        currentConnection = null;
        currentPath = null;
        serviceBinder = null;
        moduleBinder = null;
        deathRecipient = null;
        moduleDelivered = false;
    }

    private void removeBindTimeout() {
        if (bindTimeout != null) {
            worker.removeCallbacks(bindTimeout);
            bindTimeout = null;
        }
    }

    private static void unlinkDeath(IBinder binder, IBinder.DeathRecipient recipient) {
        if (binder == null) {
            return;
        }
        try {
            binder.unlinkToDeath(recipient, 0);
        } catch (RuntimeException ignored) {
            // Binder may already be dead. Cleanup is best-effort.
        }
    }

    private static IBinder getRemoteModule(IBinder toolkit, int moduleId) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TOOLKIT_DESCRIPTOR);
            data.writeInt(moduleId);
            boolean handled = toolkit.transact(
                    TRANSACTION_GET_REMOTE_MODULE, data, reply, 0);
            if (!handled) {
                throw new RemoteException("IRemoteToolkit did not handle getRemoteModule");
            }
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
