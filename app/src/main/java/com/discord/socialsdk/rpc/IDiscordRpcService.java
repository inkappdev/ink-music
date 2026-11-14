package com.discord.socialsdk.rpc;

public interface IDiscordRpcService extends android.os.IInterface {
    String DESCRIPTOR = "com.discord.socialsdk.rpc.IDiscordRpcService";
    IDiscordRpcConnection connect(long applicationId, String version, IDiscordRpcCallback callback) throws android.os.RemoteException;

    abstract class Stub extends android.os.Binder implements IDiscordRpcService {
        static final int TRANSACTION_connect = android.os.IBinder.FIRST_CALL_TRANSACTION;
        public Stub() { attachInterface(this, DESCRIPTOR); }
        public static IDiscordRpcService asInterface(android.os.IBinder binder) {
            if (binder == null) return null;
            android.os.IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            return local instanceof IDiscordRpcService ? (IDiscordRpcService) local : new Proxy(binder);
        }
        @Override public android.os.IBinder asBinder() { return this; }
        @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException {
            if (code == TRANSACTION_connect) {
                data.enforceInterface(DESCRIPTOR);
                IDiscordRpcConnection result = connect(data.readLong(), data.readString(), IDiscordRpcCallback.Stub.asInterface(data.readStrongBinder()));
                reply.writeNoException(); reply.writeStrongBinder(result != null ? result.asBinder() : null); return true;
            }
            if (code == android.os.IBinder.INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
            return super.onTransact(code, data, reply, flags);
        }
        private static final class Proxy implements IDiscordRpcService {
            private final android.os.IBinder remote;
            Proxy(android.os.IBinder remote) { this.remote = remote; }
            @Override public android.os.IBinder asBinder() { return remote; }
            @Override public IDiscordRpcConnection connect(long id, String version, IDiscordRpcCallback callback) throws android.os.RemoteException {
                android.os.Parcel data = android.os.Parcel.obtain(), reply = android.os.Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR); data.writeLong(id); data.writeString(version);
                    data.writeStrongBinder(callback != null ? callback.asBinder() : null);
                    remote.transact(TRANSACTION_connect, data, reply, 0); reply.readException();
                    return IDiscordRpcConnection.Stub.asInterface(reply.readStrongBinder());
                } finally { reply.recycle(); data.recycle(); }
            }
        }
    }
}