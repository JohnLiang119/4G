package com.tw.perapp4grouter.localvpn;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ConcurrentLinkedQueue;
import com.tw.perapp4grouter.AppLogger;

public class VPNRunnable implements Runnable {
    private static final String TAG = "VPNRunnable";
    private FileDescriptor vpnFileDescriptor;
    private ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue;
    private ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue;
    private ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue;

    public VPNRunnable(FileDescriptor vpnFileDescriptor,
                       ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue,
                       ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue,
                       ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue) {
        this.vpnFileDescriptor = vpnFileDescriptor;
        this.deviceToNetworkUDPQueue = deviceToNetworkUDPQueue;
        this.deviceToNetworkTCPQueue = deviceToNetworkTCPQueue;
        this.networkToDeviceQueue = networkToDeviceQueue;
    }

    @Override
    public void run() {
        AppLogger.INSTANCE.i(TAG, "Started");

        FileChannel vpnInput = new FileInputStream(vpnFileDescriptor).getChannel();
        FileChannel vpnOutput = new FileOutputStream(vpnFileDescriptor).getChannel();

        try {
            ByteBuffer bufferToNetwork = null;
            boolean dataSent = true;
            boolean dataReceived;
            while (!Thread.interrupted()) {
                if (dataSent)
                    bufferToNetwork = ByteBufferPool.acquire();
                else
                    bufferToNetwork.clear();

                int readBytes = vpnInput.read(bufferToNetwork);
                if (readBytes > 0) {
                    dataSent = true;
                    bufferToNetwork.flip();
                    Packet packet = new Packet(bufferToNetwork);
                    if (packet.ip4Header.version == 6) {
                        dataSent = false;
                    } else if (packet.isUDP()) {
                        deviceToNetworkUDPQueue.offer(packet);
                    } else if (packet.isTCP()) {
                        deviceToNetworkTCPQueue.offer(packet);
                    } else {
                        AppLogger.INSTANCE.w(TAG, "Unknown packet type");
                        AppLogger.INSTANCE.w(TAG, packet.ip4Header.toString());
                        dataSent = false;
                    }
                } else {
                    dataSent = false;
                }

                ByteBuffer bufferFromNetwork = networkToDeviceQueue.poll();
                if (bufferFromNetwork != null) {
                    bufferFromNetwork.flip();
                    while (bufferFromNetwork.hasRemaining())
                        vpnOutput.write(bufferFromNetwork);
                    dataReceived = true;
                    ByteBufferPool.release(bufferFromNetwork);
                } else {
                    dataReceived = false;
                }

                if (!dataSent && !dataReceived)
                    Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            AppLogger.INSTANCE.i(TAG, "Stopping");
        } catch (IOException e) {
            AppLogger.INSTANCE.w(TAG, e.toString());
        } finally {
            closeResources(vpnInput, vpnOutput);
        }
    }

    private static void closeResources(Closeable... resources) {
        for (Closeable resource : resources) {
            try {
                resource.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }
}
