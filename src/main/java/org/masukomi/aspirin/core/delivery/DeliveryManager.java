package org.masukomi.aspirin.core.delivery;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.apache.commons.pool.ObjectPool;
import org.apache.commons.pool.impl.GenericObjectPool;
import org.masukomi.aspirin.core.AspirinInternal;
import org.masukomi.aspirin.core.Helper;
import org.masukomi.aspirin.core.config.Configuration;
import org.masukomi.aspirin.core.config.ConfigurationChangeListener;
import org.masukomi.aspirin.core.config.ConfigurationMBean;
import org.masukomi.aspirin.core.dns.ResolveHost;
import org.masukomi.aspirin.core.store.mail.MailStore;
import org.masukomi.aspirin.core.store.queue.DeliveryState;
import org.masukomi.aspirin.core.store.queue.QueueInfo;
import org.masukomi.aspirin.core.store.queue.QueueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is the manager of delivery. It is instantiated by Aspirin class.
 *
 * @author Laszlo Solova
 *
 */
public final class DeliveryManager extends Thread implements ConfigurationChangeListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryManager.class);
    private final Configuration configuration;
    private MailStore mailStore;
    private final QueueStore queueStore;
    private DeliveryMaintenanceThread maintenanceThread;
    private Object mailingLock = new Object();
    private ObjectPool deliveryThreadObjectPool = null;
    private boolean running = false;
    private GenericPoolableDeliveryThreadFactory deliveryThreadObjectFactory = null;
    private Map<String, DeliveryHandler> deliveryHandlers = new HashMap<String, DeliveryHandler>();
    private final Helper helper;

    public DeliveryManager(Configuration configuration, QueueStore queueStore, MailStore mailStore) {
        this.configuration = configuration;
        this.helper = new Helper(configuration);
        // Set up default objects.
        this.setName("Aspirin-" + getClass().getSimpleName() + "-" + getId());

        // Configure pool of DeliveryThread threads
        GenericObjectPool.Config gopConf = new GenericObjectPool.Config();
        gopConf.lifo = false;
        gopConf.maxActive = configuration.getDeliveryThreadsActiveMax();
        gopConf.maxIdle = configuration.getDeliveryThreadsIdleMax();
        gopConf.maxWait = 5000;
        gopConf.testOnReturn = true;
        gopConf.whenExhaustedAction = GenericObjectPool.WHEN_EXHAUSTED_BLOCK;

        // Create DeliveryThread object factory used in pool
        deliveryThreadObjectFactory = new GenericPoolableDeliveryThreadFactory(this, configuration);

        // Create pool
        deliveryThreadObjectPool = new GenericObjectPool(deliveryThreadObjectFactory, gopConf);

        // Initialize object factory of pool
        deliveryThreadObjectFactory.init(new ThreadGroup("DeliveryThreadGroup"), deliveryThreadObjectPool);

        // Set up stores and configuration listener 
        this.queueStore = queueStore;
        queueStore.init();

        mailStore = configuration.getMailStore();
        mailStore.init();

        maintenanceThread = new DeliveryMaintenanceThread(queueStore, mailStore);
        maintenanceThread.start();

        // Set up deliveryhandlers
        // TODO create by configuration
        deliveryHandlers.put(SendMessage.class.getCanonicalName(), new SendMessage(configuration));
        deliveryHandlers.put(ResolveHost.class.getCanonicalName(), new ResolveHost());

        configuration.addListener(this);
    }

    public String add(MimeMessage mimeMessage) throws MessagingException {
        String mailid = helper.getMailID(mimeMessage);
        long expiry = helper.getExpiry(mimeMessage);
        Collection<InternetAddress> recipients = AspirinInternal.extractRecipients(mimeMessage);
        synchronized (mailingLock) {
            mailStore.set(mailid, mimeMessage);
            queueStore.add(mailid, expiry, recipients);
        }
        return mailid;
    }

    public MimeMessage get(QueueInfo qi) {
        return mailStore.get(qi.getMailid());
    }

    public void remove(String messageName) {
        synchronized (mailingLock) {
            mailStore.remove(messageName);
            queueStore.remove(messageName);
        }
    }

    @Override
    public void run() {
        running = true;
        log.info("DeliveryManager started.");
        while (running) {
            QueueInfo qi = null;
            try {
                qi = queueStore.next();
                if (qi != null) {
                    MimeMessage message = get(qi);
                    if (message == null) {
                        log.warn("No MimeMessage found for qi={}", qi);
                        qi.setResultInfo("No MimeMessage found.");
                        qi.setState(DeliveryState.FAILED);
                        release(qi);
                        continue;
                    }
                    DeliveryContext dCtx = new DeliveryContext().setQueueInfo(qi).setMessage(message);
                    log.trace("DeliveryManager.run(): Pool state. A{}/I{}", new Object[]{deliveryThreadObjectPool.getNumActive(), deliveryThreadObjectPool.getNumIdle()});
                    try {
                        log.debug("DeliveryManager.run(): Start delivery. qi={}", qi);
                        DeliveryThread dThread = (DeliveryThread) deliveryThreadObjectPool.borrowObject();
                        log.trace("DeliveryManager.run(): Borrow DeliveryThread object. dt={}: state '{}/{}'", new Object[]{dThread.getName(), dThread.getState().name(), dThread.isAlive()});
                        dThread.setContext(dCtx);
                        /*
                         * On first borrow the DeliveryThread is created and
                         * initialized, but not started, because the first time
                         * we have to set up the QueItem to deliver.
                         */
                        if (!dThread.isAlive()) {
                            dThread.start();
                        }
                    } catch (IllegalStateException ise) {
                        /*
                         * This could be happen, if thread is running, but
                         * ObjectPool is already closed. It is a normal process
                         * of Aspirin sending thread shutdown.
                         */
                        release(qi);
                    } catch (NoSuchElementException nsee) {
                        /*
                         * This happens if there is a lot of mail to send, and
                         * no idle DeliveryThread is available.
                         */
                        log.debug("DeliveryManager.run(): No idle DeliveryThread is available: {}", nsee.getMessage());
                        release(qi);
                    } catch (Exception e) {
                        log.error("DeliveryManager.run(): Failed borrow delivery thread object.", e);
                        release(qi);
                    }
                } else {
                    if (log.isTraceEnabled() && 0 < queueStore.size()) {
                        log.trace("DeliveryManager.run(): There is no sendable item in the queue. Fallback to waiting state for a minute.");
                    }
                    synchronized (this) {
                        try {
                            /*
                             * We should wait for a specified time, because some
                             * emails unsent could be sendable again.
                             */
                            wait(60000);
                        } catch (InterruptedException e) {
                            running = false;
                        }
                    }
                }

            } catch (Throwable t) {
                if (qi != null) {
                    release(qi);
                }
            }

        }
        log.info("DeliveryManager terminated.");
    }

    public boolean isRunning() {
        return running;
    }

    public void terminate() {
        running = false;
    }

    public void release(QueueInfo qi) {
        if (qi.hasState(DeliveryState.IN_PROGRESS)) {
            if (qi.isInTimeBounds()) {
                qi.setState(DeliveryState.QUEUED);
                log.trace("DeliveryManager.release(): Releasing: QUEUED. qi={}", qi);
            } else {
                qi.setState(DeliveryState.FAILED);
                log.trace("DeliveryManager.release(): Releasing: FAILED. qi={}", qi);
            }
        }
        queueStore.setSendingResult(qi);
        if (queueStore.isCompleted(qi.getMailid())) {
            queueStore.remove(qi.getMailid());
        }
        log.trace("DeliveryManager.release(): Release item '{}' with state: '{}' after {} attempts.", new Object[]{qi.getMailid(), qi.getState().name(), qi.getAttemptCount()});
    }

    public boolean isCompleted(QueueInfo qi) {
        return queueStore.isCompleted(qi.getMailid());
    }

    @Override
    public void configChanged(String parameterName) {
        synchronized (mailingLock) {
            if (parameterName.equals(Configuration.PARAM_MAILSTORE_CLASS)) {
                mailStore = configuration.getMailStore();
            } else if (parameterName.equals(ConfigurationMBean.PARAM_DELIVERY_THREADS_ACTIVE_MAX)) {
                ((GenericObjectPool) deliveryThreadObjectPool).setMaxActive(configuration.getDeliveryThreadsActiveMax());
            } else if (parameterName.equals(ConfigurationMBean.PARAM_DELIVERY_THREADS_IDLE_MAX)) {
                ((GenericObjectPool) deliveryThreadObjectPool).setMaxIdle(configuration.getDeliveryThreadsIdleMax());
            }
        }
    }

    public DeliveryHandler getDeliveryHandler(String handlerName) {
        return deliveryHandlers.get(handlerName);
    }

    public void shutdown() {
        this.running = false;
        try {
            deliveryThreadObjectPool.close();
            deliveryThreadObjectPool.clear();
        } catch (Exception e) {
            log.error("DeliveryManager.shutdown() failed.", e);
        }
        maintenanceThread.shutdown();
    }
}
