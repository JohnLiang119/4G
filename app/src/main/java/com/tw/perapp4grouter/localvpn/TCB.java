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

import com.tw.perapp4grouter.stats.FlowKey;
import com.tw.perapp4grouter.stats.FlowObserver;
import com.tw.perapp4grouter.stats.FlowObservers;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;

/**
 * Transmission Control Block
 */
public class TCB
{
    public String ipAndPort;

    public long mySequenceNum, theirSequenceNum;
    public long myAcknowledgementNum, theirAcknowledgementNum;
    public TCBStatus status;

    // TCP has more states, but we need only these
    public enum TCBStatus
    {
        SYN_SENT,
        SYN_RECEIVED,
        ESTABLISHED,
        CLOSE_WAIT,
        LAST_ACK,
    }

    public Packet referencePacket;

    public SocketChannel channel;
    public boolean waitingForNetworkData;
    public SelectionKey selectionKey;

    /** 本 flow 於統計中的身分；在首次外送 SYN 時以原始五元組建立。 */
    public FlowKey flowKey;

    /** 確保所有終止路徑只會扣除統計一次。 */
    private boolean statsClosed;

    private static final int MAX_CACHE_SIZE = 5000; // Increased to prevent active connections from being dropped
    private static LRUCache<String, TCB> tcbCache =
            new LRUCache<>(MAX_CACHE_SIZE, new LRUCache.CleanupCallback<String, TCB>()
            {
                @Override
                public void cleanup(Map.Entry<String, TCB> eldest)
                {
                    // 快取淘汰同樣是終止路徑，必須把統計紀錄一併移除
                    eldest.getValue().closeChannel();
                    eldest.getValue().notifyFlowClosed();
                }
            });

    public static TCB getTCB(String ipAndPort)
    {
        synchronized (tcbCache)
        {
            return tcbCache.get(ipAndPort);
        }
    }

    public static void putTCB(String ipAndPort, TCB tcb)
    {
        synchronized (tcbCache)
        {
            tcbCache.put(ipAndPort, tcb);
        }
    }

    public static int getActiveTCBCount() {
        synchronized (tcbCache) {
            return tcbCache.size();
        }
    }

    public static int getMaxTCBCount() {
        return MAX_CACHE_SIZE;
    }

    public TCB(String ipAndPort, long mySequenceNum, long theirSequenceNum, long myAcknowledgementNum, long theirAcknowledgementNum,
               SocketChannel channel, Packet referencePacket)
    {
        this.ipAndPort = ipAndPort;

        this.mySequenceNum = mySequenceNum;
        this.theirSequenceNum = theirSequenceNum;
        this.myAcknowledgementNum = myAcknowledgementNum;
        this.theirAcknowledgementNum = theirAcknowledgementNum;

        this.channel = channel;
        this.referencePacket = referencePacket;
    }

    /**
     * 所有 TCP 終止路徑（連線失敗、RST、雙向 FIN 完成、channel 錯誤、快取淘汰、
     * 引擎關閉）共用的唯一清理入口，且為冪等操作。
     */
    public static void closeTCB(TCB tcb)
    {
        tcb.closeChannel();
        synchronized (tcbCache)
        {
            tcbCache.remove(tcb.ipAndPort);
        }
        tcb.notifyFlowClosed();
    }

    public static void closeAll()
    {
        java.util.List<TCB> closing = new java.util.ArrayList<>();
        synchronized (tcbCache)
        {
            Iterator<Map.Entry<String, TCB>> it = tcbCache.entrySet().iterator();
            while (it.hasNext())
            {
                TCB tcb = it.next().getValue();
                tcb.closeChannel();
                closing.add(tcb);
                it.remove();
            }
        }
        // 在鎖外通知，避免統計回呼與封包執行緒互相等待
        for (TCB tcb : closing)
        {
            tcb.notifyFlowClosed();
        }
    }

    /**
     * 把本 flow 從作用中統計移除。重複呼叫不會重複扣除。
     */
    void notifyFlowClosed()
    {
        FlowKey key;
        synchronized (this)
        {
            if (statsClosed)
                return;
            statsClosed = true;
            key = flowKey;
        }

        if (key == null)
            return;

        FlowObserver observer = FlowObservers.get();
        if (observer != null)
        {
            // observer 內部會以 vpnGeneration 把關，舊 session 的事件不會影響新 session
            observer.onFlowClosed(key);
        }
    }

    private void closeChannel()
    {
        try
        {
            channel.close();
        }
        catch (IOException e)
        {
            // Ignore
        }
    }
}
