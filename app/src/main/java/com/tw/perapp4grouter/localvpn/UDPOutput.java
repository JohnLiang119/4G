/*
** Copyright 2015, Mohamed Naufal
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.tw.perapp4grouter.localvpn;

import android.net.VpnService;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

public class UDPOutput implements Runnable
{
    private static final String TAG = UDPOutput.class.getSimpleName();

    private VpnService vpnService;
    private ConcurrentLinkedQueue<Packet> inputQueue;
    private Selector selector;

    private static final int MAX_CACHE_SIZE = 5000; // Increased to prevent active connections from being dropped
    private static LRUCache<String, UdpAssociation> channelCache =
            new LRUCache<>(MAX_CACHE_SIZE, new LRUCache.CleanupCallback<String, UdpAssociation>()
            {
                @Override
                public void cleanup(Map.Entry<String, UdpAssociation> eldest)
                {
                    closeAssociation(eldest.getValue());
                }
            });

    public static int getActiveUDPCount() {
        synchronized (channelCache) {
            return channelCache.size();
        }
    }

    public static int getMaxUDPCount() {
        return MAX_CACHE_SIZE;
    }

    public UDPOutput(ConcurrentLinkedQueue<Packet> inputQueue, Selector selector, VpnService vpnService)
    {
        this.inputQueue = inputQueue;
        this.selector = selector;
        this.vpnService = vpnService;
    }

    private static final long UDP_EXPIRY_MS = 60000;
    private long lastSweepTime = 0;

    private void sweepExpiredAssociations(long now) {
        synchronized (channelCache) {
            Iterator<Map.Entry<String, UdpAssociation>> it = channelCache.entrySet().iterator();
            while (it.hasNext()) {
                UdpAssociation association = it.next().getValue();
                if (now - association.lastActivityAt >= UDP_EXPIRY_MS) {
                    closeAssociation(association);
                    it.remove();
                }
            }
        }
    }

    @Override
    public void run()
    {
        Log.i(TAG, "Started");
        try
        {

            Thread currentThread = Thread.currentThread();
            while (true)
            {
                Packet currentPacket;
                // TODO: Block when not connected
                do
                {
                    long now = com.tw.perapp4grouter.stats.SystemMonotonicClock.INSTANCE.elapsedMillis();
                    if (now - lastSweepTime > 1000) {
                        lastSweepTime = now;
                        sweepExpiredAssociations(now);
                    }

                    currentPacket = inputQueue.poll();
                    if (currentPacket != null)
                        break;
                    Thread.sleep(10);
                } while (!currentThread.isInterrupted());

                if (currentThread.isInterrupted())
                    break;

                InetAddress destinationAddress = currentPacket.ip4Header.destinationAddress;
                int destinationPort = currentPacket.udpHeader.destinationPort;
                int sourcePort = currentPacket.udpHeader.sourcePort;
                InetAddress sourceAddress = currentPacket.ip4Header.sourceAddress;

                String ipAndPort = destinationAddress.getHostAddress() + ":" + destinationPort + ":" + sourcePort;
                UdpAssociation association;
                synchronized (channelCache) {
                    association = channelCache.get(ipAndPort);
                }
                DatagramChannel outputChannel;
                if (association == null) {
                    outputChannel = DatagramChannel.open();
                    vpnService.protect(outputChannel.socket());
                    LocalVpnNetworkHelper.bindDatagramSocketToNetwork(outputChannel.socket());
                    try
                    {
                        outputChannel.connect(new InetSocketAddress(destinationAddress, destinationPort));
                    }
                    catch (IOException e)
                    {
                        Log.e(TAG, "Connection error: " + ipAndPort, e);
                        try { outputChannel.close(); } catch (Exception ex) {}
                        ByteBufferPool.release(currentPacket.backingBuffer);
                        continue;
                    }
                    outputChannel.configureBlocking(false);

                    com.tw.perapp4grouter.stats.FlowKey flowKey = null;
                    com.tw.perapp4grouter.stats.FlowObserver observer = com.tw.perapp4grouter.stats.FlowObservers.get();
                    if (observer != null) {
                        flowKey = observer.keyFor(
                                com.tw.perapp4grouter.stats.FlowProtocol.UDP,
                                sourceAddress.getHostAddress(),
                                sourcePort,
                                destinationAddress.getHostAddress(),
                                destinationPort
                        );
                        observer.onFlowOpened(flowKey);
                    }

                    association = new UdpAssociation(outputChannel, currentPacket, flowKey, com.tw.perapp4grouter.stats.SystemMonotonicClock.INSTANCE.elapsedMillis());

                    currentPacket.swapSourceAndDestination();

                    selector.wakeup();
                    outputChannel.register(selector, SelectionKey.OP_READ, association);

                    synchronized (channelCache) {
                        channelCache.put(ipAndPort, association);
                    }
                } else {
                    outputChannel = association.channel;
                    association.lastActivityAt = com.tw.perapp4grouter.stats.SystemMonotonicClock.INSTANCE.elapsedMillis();
                    com.tw.perapp4grouter.stats.FlowObserver observer = com.tw.perapp4grouter.stats.FlowObservers.get();
                    if (observer != null && association.flowKey != null) {
                        observer.onFlowActivity(association.flowKey);
                    }
                }

                try
                {
                    ByteBuffer payloadBuffer = currentPacket.backingBuffer;
                    while (payloadBuffer.hasRemaining())
                        outputChannel.write(payloadBuffer);
                }
                catch (IOException e)
                {
                    Log.e(TAG, "Network write error: " + ipAndPort, e);
                    synchronized (channelCache) {
                        channelCache.remove(ipAndPort);
                    }
                    closeAssociation(association);
                }
                ByteBufferPool.release(currentPacket.backingBuffer);
            }
        }
        catch (InterruptedException e)
        {
            Log.i(TAG, "Stopping");
        }
        catch (IOException e)
        {
            Log.i(TAG, e.toString(), e);
        }
        finally
        {
            closeAll();
        }
    }

    private void closeAll()
    {
        synchronized (channelCache) {
            Iterator<Map.Entry<String, UdpAssociation>> it = channelCache.entrySet().iterator();
            while (it.hasNext())
            {
                closeAssociation(it.next().getValue());
                it.remove();
            }
        }
    }

    static void closeAssociation(UdpAssociation association)
    {
        if (association != null) {
            association.closeChannel();
            association.notifyFlowClosed();
        }
    }
}
