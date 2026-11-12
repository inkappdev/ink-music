package com.discord.socialsdk.rpc;

public interface IDiscordRpcCallback extends android.os.IInterface {
    String DESCRIPTOR = "com.discord.socialsdk.rpc.IDiscordRpcCallback";
    void onFrame(String frame) throws android.os.RemoteException;
    void onClose(int code, String message) throws android.os.RemoteException;

    abstract class Stub extends android.os.Binder implements IDiscordRpcCallback {
        static final int TRANSACTION_onFrame = android.os.IBinder.FIRST_CALL_TRANSACTION;
        static final int TRANSACTION_onClose = android.os.IBinder.FIRST_CALL_TRANSACTION + 1;
        public Stub() { attachInterface(this, DESCRIPTOR); }
        public static IDiscordRpcCallback asInterface(android.os.IBinder binder) {
            if (binder == null) return null;
            android.os.IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            return local instanceof IDiscordRpcCallback ? (IDiscordRpcCallback) local : new Proxy(binder);
        }
        @Override public android.os.IBinder asBinder() { return this; }
        @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException {
            if (code == TRANSACTION_onFrame) { data.enforceInterface(DESCRIPTOR); onFrame(data.readString()); reply.writeNoException(); return true; }
            if (code == TRANSACTION_onClose) { data.enforceInterface(DESCRIPTOR); onClose(data.readInt(), data.readString()); reply.writeNoException(); return true; }
            if (code == android.os.IBinder.INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
            return super.onTransact(code, data, reply, flags);
        }
        private static final class Proxy implements IDiscordRpcCallback {
            private final android.os.IBinder remote;
            Proxy(android.os.IBinder remote) { this.remote = remote; }
            @Override public android.os.IBinder asBinder() { return remote; }
            @Override public void onFrame(String frame) throws android.os.RemoteException {
                android.os.Parcel data = android.os.Parcel.obtain(), reply = android.os.Parcel.obtain();
                try { data.writeInterfaceToken(DESCRIPTOR); data.writeString(frame); remote.transact(TRANSACTION_onFrame, data, reply, 0); reply.readException(); }
                finally { reply.recycle(); data.recycle(); }
            }
            @Override public void onClose(int code, String message) throws android.os.RemoteException {
                android.os.Parcel data = android.os.Parcel.obtain(), reply = android.os.Parcel.obtain();
                try { data.writeInterfaceToken(DESCRIPTOR); data.writeInt(code); data.writeString(message); remote.transact(TRANSACTION_onClose, data, reply, 0); reply.readException(); }
                finally { reply.recycle(); data.recycle(); }
            }
        }
    }
}