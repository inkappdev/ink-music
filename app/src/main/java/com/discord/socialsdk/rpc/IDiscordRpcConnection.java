package com.discord.socialsdk.rpc;

public interface IDiscordRpcConnection extends android.os.IInterface {
    String DESCRIPTOR = "com.discord.socialsdk.rpc.IDiscordRpcConnection";
    void sendFrame(String frame) throws android.os.RemoteException;
    void disconnect() throws android.os.RemoteException;

    abstract class Stub extends android.os.Binder implements IDiscordRpcConnection {
        static final int TRANSACTION_sendFrame = android.os.IBinder.FIRST_CALL_TRANSACTION;
        static final int TRANSACTION_disconnect = android.os.IBinder.FIRST_CALL_TRANSACTION + 1;
        public Stub() { attachInterface(this, DESCRIPTOR); }
        public static IDiscordRpcConnection asInterface(android.os.IBinder binder) {
            if (binder == null) return null;
            android.os.IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            return local instanceof IDiscordRpcConnection ? (IDiscordRpcConnection) local : new Proxy(binder);
        }
        @Override public android.os.IBinder asBinder() { return this; }
        @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException {
            if (code == TRANSACTION_sendFrame) { data.enforceInterface(DESCRIPTOR); sendFrame(data.readString()); reply.writeNoException(); return true; }
            if (code == TRANSACTION_disconnect) { data.enforceInterface(DESCRIPTOR); disconnect(); reply.writeNoException(); return true; }
            if (code == android.os.IBinder.INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
            return super.onTransact(code, data, reply, flags);
        }
        private static final class Proxy implements IDiscordRpcConnection {
            private final android.os.IBinder remote;
            Proxy(android.os.IBinder remote) { this.remote = remote; }
            @Override public android.os.IBinder asBinder() { return remote; }
            @Override public void sendFrame(String frame) throws android.os.RemoteException {
                android.os.Parcel data = android.os.Parcel.obtain(), reply = android.os.Parcel.obtain();
                try { data.writeInterfaceToken(DESCRIPTOR); data.writeString(frame); remote.transact(TRANSACTION_sendFrame, data, reply, 0); reply.readException(); }
                finally { reply.recycle(); data.recycle(); }
            }
            @Override public void disconnect() throws android.os.RemoteException {
                android.os.Parcel data = android.os.Parcel.obtain(), reply = android.os.Parcel.obtain();
                try { data.writeInterfaceToken(DESCRIPTOR); remote.transact(TRANSACTION_disconnect, data, reply, 0); reply.readException(); }
                finally { reply.recycle(); data.recycle(); }
            }
        }
    }
}