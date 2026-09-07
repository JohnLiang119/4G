package com.tw.perapp4grouter.localvpn;

import java.nio.channels.DatagramChannel;

import com.tw.perapp4grouter.stats.FlowKey;
import com.tw.perapp4grouter.stats.FlowObserver;
import com.tw.perapp4grouter.stats.FlowObservers;

public class UdpAssociation {
    public DatagramChannel channel;
    public Packet referencePacket;
    public FlowKey flowKey;
    public long lastActivityAt;

    private boolean statsClosed;

    public UdpAssociation(DatagramChannel channel, Packet referencePacket, FlowKey flowKey, long lastActivityAt) {
        this.channel = channel;
        this.referencePacket = referencePacket;
        this.flowKey = flowKey;
        this.lastActivityAt = lastActivityAt;
    }

    public void closeChannel() {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (java.io.IOException e) {
            // Ignore
        }
    }

    public void notifyFlowClosed() {
        FlowKey key;
        synchronized (this) {
            if (statsClosed) return;
            statsClosed = true;
            key = flowKey;
        }

        if (key == null) return;

        FlowObserver observer = FlowObservers.get();
        if (observer != null) {
            observer.onFlowClosed(key);
        }
    }
}
