package org.masukomi.aspirin.core.dns;

import java.util.Collection;

import javax.mail.URLName;

import org.masukomi.aspirin.core.AspirinInternal;
import org.masukomi.aspirin.core.Helper;
import org.masukomi.aspirin.core.delivery.DeliveryContext;
import org.masukomi.aspirin.core.delivery.DeliveryException;
import org.masukomi.aspirin.core.delivery.DeliveryHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This delivery handler resolve recipient's MX records and append them to the
 * delivery context. INPUT (REQUIRED) variables: - none OUTPUT (CREATED)
 * variables: - targetservers Collection<URLName>
 *
 * @author Laszlo Solova
 *
 */
public class ResolveHost implements DeliveryHandler {

    private static final Logger log = LoggerFactory.getLogger(ResolveHost.class);

    @Override
    public void handle(DeliveryContext dCtx) throws DeliveryException {
        String currentRecipient = dCtx.getQueueInfo().getRecipient();
        // Get host MX records
        String host = currentRecipient.substring(currentRecipient.lastIndexOf("@") + 1);
        Collection<URLName> targetServers = null;
        try {
            targetServers = DnsResolver.getMXRecordsForHost(host);
            /*
             * If there was no target server, could be caused by a temporary
             * failure in domain name resolving. So we should to deliver this
             * email later.
             */
            if (targetServers == null || targetServers.isEmpty()) {
                log.warn("ResolveHost.handle(): No mail server found for: '{}'.", new Object[]{host});
                throw new DeliveryException("No MX record found. Temporary failure, trying again.", false);
            }
            log.trace("ResolveHost.handle(): {} servers found for '{}'.", new Object[]{targetServers.size(), host});
            dCtx.addContextVariable("targetservers", targetServers);
        } catch (DeliveryException de) {
            throw de;
        } catch (Exception e) {
            log.error("ResolveHost.handle(): Could not get MX for host '" + host + "' defined by recipient '" + currentRecipient + "'.", e);
            throw new DeliveryException("No MX record found. Temporary failure, trying again.", false);
        }

    }
}
