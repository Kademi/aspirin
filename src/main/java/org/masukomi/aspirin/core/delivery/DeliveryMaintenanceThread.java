package org.masukomi.aspirin.core.delivery;

import java.util.List;

import org.masukomi.aspirin.core.store.mail.MailStore;
import org.masukomi.aspirin.core.store.queue.QueueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is a maintenance thread, to clean up stores - remove all finished mails
 * from these objects. This is very useful for long-time runs.
 *
 * @author Laszlo Solova
 *
 */
public class DeliveryMaintenanceThread extends Thread {

    private static final Logger log = LoggerFactory.getLogger(DeliveryMaintenanceThread.class);
    private boolean running = false;
    private final QueueStore queueStore;
    private final MailStore mailStore;

    public DeliveryMaintenanceThread(QueueStore queueStore, MailStore mailStore) {
        this.queueStore = queueStore;
        this.mailStore = mailStore;
        this.setDaemon(true);
    }

    @Override
    public void run() {
        log.info("Maintenance thread started.");
        running = true;
        while (running) {
            try {
                synchronized (this) {
                    wait(3600000);
                }
            } catch (InterruptedException ie) {
                running = false;
                log.info("Maintenance thread goes down.");
            }
            // Maintain queues in every hour
            try {
                List<String> usedMailIds = queueStore.clean();
                List<String> mailStoreMailIds = mailStore.getMailIds();
                log.debug("Maintenance running: usedMailIds: {}, mailStoreMailIds: {}.", new Object[]{usedMailIds.size(), mailStoreMailIds.size()});
                if (mailStoreMailIds.removeAll(usedMailIds)) {
                    for (String unusedMailId : mailStoreMailIds) {
                        mailStore.remove(unusedMailId);
                    }
                }
            } catch (Exception e) {
                log.error("Maintenance failed.", e);
            }
        }
    }

    public void shutdown() {
        running = false;
        synchronized (this) {
            notify();
        }
    }
}
