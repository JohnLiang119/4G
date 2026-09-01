package com.tw.perapp4grouter.localvpn;

import android.net.Network;
import android.os.Build;
import java.io.FileDescriptor;
import java.net.DatagramSocket;
import java.net.Socket;
import com.tw.perapp4grouter.AppLogger;

public class LocalVpnNetworkHelper {
    public static Network currentNetwork = null;

    public static void bindSocketToNetwork(Socket socket) {
        if (currentNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                currentNetwork.bindSocket(socket);
                AppLogger.INSTANCE.d("LocalVPN", "Socket bound to 4G Network");
            } catch (Exception e) {
                AppLogger.INSTANCE.e("LocalVPN", "Failed to bind Socket to 4G Network", e);
            }
        }
    }

    public static void bindDatagramSocketToNetwork(DatagramSocket socket) {
        if (currentNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                currentNetwork.bindSocket(socket);
                AppLogger.INSTANCE.d("LocalVPN", "DatagramSocket bound to 4G Network");
            } catch (Exception e) {
                AppLogger.INSTANCE.e("LocalVPN", "Failed to bind DatagramSocket to 4G Network", e);
            }
        }
    }
}
