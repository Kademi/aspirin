package org.masukomi.aspirin.core.delivery;

import javax.mail.MessagingException;
import javax.mail.Session;

import org.apache.commons.pool.ObjectPool;
import org.masukomi.aspirin.core.config.Configuration;
import org.masukomi.aspirin.core.dns.ResolveHost;
import org.masukomi.aspirin.core.store.queue.DeliveryState;
import org.masukomi.aspirin.core.store.queue.QueueInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Based on original RemoteDelivery class.
 *
 * @author Laszlo Solova
 *
 */
public class DeliveryThread extends Thread {

    private static final Logger log = LoggerFactory.getLogger(DeliveryThread.class);
    private final DeliveryManager deliveryManager;
    private final Configuration configuration;
    private boolean running = true;
    private ObjectPool parentObjectPool = null;
    private DeliveryContext dCtx = null;

    public DeliveryThread(ThreadGroup parentThreadGroup, DeliveryManager deliveryManager, Configuration configuration) {
        super(parentThreadGroup, DeliveryThread.class.getSimpleName());
        this.deliveryManager = deliveryManager;
        this.configuration = configuration;
    }

    public ObjectPool getParentObjectPool() {
        return parentObjectPool;
    }

    public void setParentObjectPool(ObjectPool parentObjectPool) {
        this.parentObjectPool = parentObjectPool;
    }

    public void shutdown() {
        log.debug("DeliveryThread ({}).shutdown(): Called.", getName());
        running = false;
        synchronized (this) {
            notify();
        }
    }

    @Override
    public void run() {
        while (running) {
            synchronized (this) {
                if (dCtx == null) {
                    // Wait for next QueueInfo to deliver
                    try {
                        if (running) {
                            log.trace("DeliveryThread ({}).run(): Wait for next sendable item.", getName());
                            wait(60000);
                            continue;
                        }
                    } catch (InterruptedException ie) /*
                     * On interrupt we shutdown this thread and remove from
                     * pool. It could be a QueueInfo in the qi variable, so we
                     * try to release it before finish the work.
                     */ {
                        if (dCtx != null) {
                            log.trace("DeliveryThread ({}).run(): Release item after interruption. qi={}", new Object[]{getName(), dCtx});
                            deliveryManager.release(dCtx.getQueueInfo());
                            dCtx = null;
                        }
                        running = false;
                        try {
                            log.trace("DeliveryThread ({}).run(): Invalidate DeliveryThread object in the pool.", getName());
                            parentObjectPool.invalidateObject(this);
                        } catch (Exception e) {
                            throw new RuntimeException("The object could not be invalidated in the pool.", e);
                        }
                    }

                }
            }
            // Try to deliver the QueueInfo
            try {
                if (dCtx != null) {
                    log.trace("DeliveryThread ({}).run(): Call delivering... dCtx={}", new Object[]{getName(), dCtx});
                    deliver(dCtx, configuration.newMailSession());

                    // This is the normal workflow, when everything has worked nicely
                    deliveryManager.release(dCtx.getQueueInfo());
                    dCtx = null;
                }
            } catch (Exception e) {
                log.error("DeliveryThread (" + getName() + ").run(): Could not deliver message. dCtx={" + dCtx + "}", e);
            } finally /*
             * Sometimes QueueInfo's status could be IN_PROCESS. This QueueInfo
             * have to be released before we finishing this round of running.
             * After releasing the dCtx variable will be nullified.
             */ {
                if (dCtx != null && !dCtx.getQueueInfo().isSendable()) {
                    deliveryManager.release(dCtx.getQueueInfo());
                    dCtx = null;
                }
            }
            if (dCtx == null) {
                try {
                    log.trace("DeliveryThread ({}).run(): Try to give back DeliveryThread object into the pool.", getName());
                    parentObjectPool.returnObject(this);
                } catch (Exception e) {
                    log.error("DeliveryThread (" + getName() + ").run(): The object could not be returned into the pool.", e);
                    this.shutdown();
                }
            }
        }
    }

    public void setContext(DeliveryContext dCtx) throws MessagingException {
        /*
         * If the dCtx variable is not null, then the previous item could be in.
         * If the previous item is not ready to send and is not completed, we
         * have to try send this item with this thread. After this thread is
         * waked up, there were thrown an Exception.
         */
        synchronized (this) {
            if (this.dCtx != null) {
                if (this.dCtx.getQueueInfo().hasState(org.masukomi.aspirin.core.store.queue.DeliveryState.IN_PROGRESS)) {
                    notify();
                }
                throw new MessagingException("The previous QuedItem was not removed from this thread.");
            }
            this.dCtx = dCtx;
            log.trace("DeliveryThread ({}).setQuedItem(): Item was set. qi={}", new Object[]{getName(), dCtx});
            notify();
        }
    }
    
    private long numDelivered;
    private DeliveryContext currentlyDelivering;
    private Long timeLastStarted;
    private Long timeLastCompleted;
    private long lastDuration;
    private long totalDuration;

    public long getNumDelivered() {
        return numDelivered;
    }

    public DeliveryContext getCurrentlyDelivering() {
        return currentlyDelivering;
    }

    public String getCurrentlyDeliveringEmail() {
        if( currentlyDelivering == null   ) {
            return null;
        } 
        return currentlyDelivering.getQueueInfo().getRecipient();
    }
    
    public Long getTimeLastStarted() {
        return timeLastStarted;
    }

    public Long getTimeLastCompleted() {
        return timeLastCompleted;
    }

    public long getLastDuration() {
        return lastDuration;
    }

    public long getTotalDuration() {
        return totalDuration;
    }
    
    
   

    private void deliver(DeliveryContext dCtx, Session session) {
        log.info("DeliveryThread ({}).deliver(): Starting mail delivery. qi={}", new Object[]{getName(), dCtx});
        
        numDelivered++;
        currentlyDelivering = dCtx;
        long tm = System.currentTimeMillis();
        timeLastStarted = tm;
        
        try {
            String[] handlerList = new String[]{
                ResolveHost.class.getCanonicalName(),
                SendMessage.class.getCanonicalName()
            };
            QueueInfo qInfo = dCtx.getQueueInfo();
            for (String handlerName : handlerList) {
                try {
                    DeliveryHandler handler = deliveryManager.getDeliveryHandler(handlerName);
                    log.info("deliver using: " + handler.getClass());
                    handler.handle(dCtx);
                } catch (DeliveryException de) {
                    qInfo.setResultInfo(de.getMessage());
                    log.info("DeliveryThread ({}).deliver(): Mail delivery failed: {}. qi={}", new Object[]{getName(), qInfo.getResultInfo(), dCtx});
                    if (de.isPermanent()) {
                        qInfo.setState(DeliveryState.FAILED);
                    } else {
                        qInfo.setState(DeliveryState.QUEUED);
                    }
                    return;
                }
            }
            if (qInfo.hasState(DeliveryState.IN_PROGRESS)) {
                if (qInfo.getResultInfo() == null) {
                    qInfo.setResultInfo("250 OK");
                }
                log.info("DeliveryThread ({}).deliver(): Mail delivery success: {}. qi={}", new Object[]{getName(), qInfo.getResultInfo(), dCtx});
                qInfo.setState(DeliveryState.SENT);
            }
        } finally {
            long fin = System.currentTimeMillis();
            lastDuration = fin - tm;
            totalDuration += lastDuration;
            timeLastCompleted = fin;
        }
    }
}
