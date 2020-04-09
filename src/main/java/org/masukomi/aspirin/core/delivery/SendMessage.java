package org.masukomi.aspirin.core.delivery;

import com.sun.mail.smtp.SMTPTransport;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collection;
import java.util.Iterator;
import java.util.Properties;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.URLName;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.masukomi.aspirin.core.store.queue.DeliveryState;

import java.util.Date;
import javax.mail.Address;
import javax.mail.Message;
import org.masukomi.aspirin.core.config.Configuration;
import org.masukomi.aspirin.core.store.mail.MailStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Laszlo Solova
 *
 */
public class SendMessage implements DeliveryHandler {

    private static final Logger log = LoggerFactory.getLogger(SendMessage.class);
    private final Configuration configuration;
    private final MailStore mailStore;

    public SendMessage(Configuration configuration, MailStore mailStore) {
        this.configuration = configuration;
        this.mailStore = mailStore;
    }

    @Override
    public void handle(DeliveryContext dCtx) throws DeliveryException {
        // Collect sending informations
        Collection<URLName> targetServers = dCtx.getContextVariable("targetservers");

        Session session = configuration.newMailSession();

        //MimeMessage message = dCtx.getMessage();
        MimeMessage message = mailStore.get(dCtx.getQueueInfo().getMailid());
        if (message == null) {
            log.info("Got a null message");
            return;
        }

        // Prepare and send
        Iterator<URLName> urlnIt = targetServers.iterator();
        InternetAddress[] addr;
        try {
            addr = new InternetAddress[]{new InternetAddress(dCtx.getQueueInfo().getRecipient())};
        } catch (AddressException e) {
            throw new DeliveryException("Recipient could not be parsed:" + dCtx.getQueueInfo().getRecipient(), true, e);
        }
        boolean sentSuccessfully = false;
        while (!sentSuccessfully && urlnIt.hasNext()) {
            try {
                URLName outgoingMailServer = urlnIt.next();
                Properties props = session.getProperties();
                if (message.getSender() == null) {
                    props.put("mail.smtp.from", "<>");
                    log.debug("SendMessage.handle(): Attempting delivery of '{}' to recipient '{}' on host '{}' from unknown sender", new Object[]{dCtx.getQueueInfo().getMailid(), dCtx.getQueueInfo().getRecipient(), outgoingMailServer});
                } else {
                    String sender = message.getSender().toString();
                    props.put("mail.smtp.from", sender);
                    log.debug("SendMessage.handle(): Attempting delivery of '{}' to recipient '{}' on host '{}' from sender '{}'", new Object[]{dCtx.getQueueInfo().getMailid(), dCtx.getQueueInfo().getRecipient(), outgoingMailServer, sender});
                }
                session.setDebug(true); // BM: just for testing
                ByteArrayOutputStream debugOut = new ByteArrayOutputStream();
                PrintStream debugOutPs = new PrintStream(debugOut);
                session.setDebugOut(debugOutPs);
                long tm = System.currentTimeMillis();
                Transport transport = null;
                try {
                    transport = session.getTransport(outgoingMailServer);
                    log.info("handle: using transport={} for outgoingMailServer={}, transport class=" + transport.getClass(), transport, outgoingMailServer);
                    try {
                        transport.connect();
                        Address[] addresses = new Address[addr.length];
                        int i = 0;
                        for (InternetAddress add : addr) {
                            log.info("sendMessage to: {}", add.getAddress());
                            addresses[i++] = add;
                        }
                        Date now = new Date();
                        message.setSentDate(now);

                        if (message.getRecipients(Message.RecipientType.TO) == null) {
                            message.setRecipients(Message.RecipientType.TO, addresses);
                        }
                        long nowMillis = System.currentTimeMillis();
//                        transport.addTransportListener(new TransportListener() {
//
//                            @Override
//                            public void messageDelivered(TransportEvent te) {
//                                log.info("messageDelivered {}", te);
//                            }
//
//                            @Override
//                            public void messageNotDelivered(TransportEvent te) {
//                                log.info("messageDelivered {}", te);
//                            }
//
//                            @Override
//                            public void messagePartiallyDelivered(TransportEvent te) {
//                                log.info("messagePartiallyDelivered {}", te);
//                            }
//                        });
                        transport.sendMessage(message, addr);
                        if (transport instanceof SMTPTransport) {
                            String response = ((SMTPTransport) transport).getLastServerResponse();
                            if (response != null) {
                                log.info("SendMessage.handle(): Last server response: {}.", response);
                                dCtx.getQueueInfo().setResultInfo(response);
                            } else {
                                dCtx.getQueueInfo().setResultInfo("No server response after " + (System.currentTimeMillis() - nowMillis) + "ms connecting to " + outgoingMailServer);
                            }
                        } else {
                            dCtx.getQueueInfo().setResultInfo("Unknown transport: " + transport);
                        }
                    } catch (MessagingException me) {
                        /*
                         * Catch on connection error only.
                         */
//                        if (resolveException(me) instanceof ConnectException) {
                        log.warn("SendMessage.handle(): Connection failed. ", me);
                        if (!urlnIt.hasNext()) {
                            throw me;
                        } else {
                            log.warn("SendMessage.handle(): Server failed, " + outgoingMailServer + " of " + targetServers.size() + ", trying next server for this recipient", me);
                            continue;
                        }
//                        } else {
//                            log.error("Exception sending message with messageId: " + message.getMessageID(), me);
//                            throw me;
//                        }
                    }

                    tm = System.currentTimeMillis() - tm;
                    log.info("SendMessage.handle(): Mail '{}' sent successfully to '{}' duration={}ms", new Object[]{dCtx.getQueueInfo().getMailid(), outgoingMailServer, tm});
                    sentSuccessfully = true;
                    dCtx.addContextVariable("newstate", DeliveryState.SENT);
                } finally {
                    if (transport != null) {
                        transport.close();
                        transport = null;
                    }

                    String debug = debugOut.toString();
                    dCtx.getQueueInfo().setLogs(debug);
                    log.info("SendMessage.handle(): debug results: {}", debug);
                }
            } catch (MessagingException me) {
                String exMessage = resolveException(me).getMessage();
                log.warn("SendMessage: messaging exception: " + exMessage);
                if ('5' == exMessage.charAt(0)) {
                    throw new DeliveryException(exMessage, true);
                } else {
                    throw new DeliveryException(exMessage, false);
                }
            } // end catch
        } // end while
        if (!sentSuccessfully) {
            throw new DeliveryException("SendMessage.handle(): Mail '{}' sending failed, try later.", false);
        }
    }

    private Exception resolveException(MessagingException msgExc) {
        MessagingException me = msgExc;
        Exception nextException = null;
        Exception lastException = msgExc;
        while ((nextException = me.getNextException()) != null) {
            lastException = nextException;
            if (MessagingException.class.getCanonicalName().equals(nextException.getClass().getCanonicalName())) {
                me = (MessagingException) nextException;
            } else {
                break;
            }
        }
        return lastException;
    }
}
