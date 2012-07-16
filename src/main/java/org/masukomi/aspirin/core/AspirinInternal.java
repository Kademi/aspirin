package org.masukomi.aspirin.core;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMessage.RecipientType;
import org.masukomi.aspirin.Aspirin;

import org.masukomi.aspirin.core.config.Configuration;
import org.masukomi.aspirin.core.delivery.DeliveryManager;
import org.masukomi.aspirin.core.listener.AspirinListener;
import org.masukomi.aspirin.core.listener.ListenerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inside factory and part provider class.
 *
 * @author Laszlo Solova
 *
 */
public class AspirinInternal {

    private static final Logger log = LoggerFactory.getLogger(AspirinInternal.class);
    
    /**
     * This session is used to generate new MimeMessage objects.
     */
    private volatile Session defaultSession = null;
    /**
     * This counter is used to generate unique message ids.
     */
    private Integer idCounter = 0;
    private Object idCounterLock = new Object();
    /**
     * Configuration object of Aspirin.
     */
    private final Configuration configuration;
    /**
     * AspirinListener management object. Create on first request.
     */
    private final ListenerManager listenerManager;
    /**
     * Delivery and QoS service management. Create on first request.
     */
    private final DeliveryManager deliveryManager;
    
    private final Helper helper;

    public AspirinInternal(Configuration configuration, DeliveryManager deliveryManager, ListenerManager listenerManager) {
        this.configuration = configuration;
        this.deliveryManager = deliveryManager;
        this.listenerManager = listenerManager;
        helper = new Helper(configuration);
    }

    public void start() {
        if (!deliveryManager.isAlive()) {
            deliveryManager.start();
        }        
    }
    
    /**
     * You can get configuration object, which could be changed to set up new
     * values. Please use this method to set up your Aspirin instance. Of course
     * default values are enough to simple mail sending.
     *
     * @return Configuration object of Aspirin
     */
    public Configuration getConfiguration() {
        return configuration;
    }

    /**
     * Add MimeMessage to deliver it.
     *
     * @param msg MimeMessage to deliver.
     * @throws MessagingException If delivery add failed.
     */
    protected void add(MimeMessage msg) throws MessagingException {
        if (!deliveryManager.isAlive()) {
            deliveryManager.start();
        }
        deliveryManager.add(msg);
    }

    /**
     * Add MimeMessage to delivery.
     *
     * @param msg MimeMessage
     * @param expiry Expiration of this email in milliseconds from now.
     * @throws MessagingException If delivery add failed.
     */
    public void add(MimeMessage msg, long expiry) throws MessagingException {
        if (0 < expiry) {
            helper.setExpiry(msg, expiry);
        }
        add(msg);
    }

    /**
     * Add mail delivery status listener.
     *
     * @param listener AspirinListener object
     */
    public void addListener(AspirinListener listener) {
        listenerManager.add(listener);
    }

    /**
     * Remove an email from delivery.
     *
     * @param mailid Unique Aspirin ID of this email.
     * @throws MessagingException If removing failed.
     */
    public void remove(String mailid) throws MessagingException {
        deliveryManager.remove(mailid);
    }

    /**
     * Remove delivery status listener.
     *
     * @param listener AspirinListener
     */
    public void removeListener(AspirinListener listener) {
        if (listenerManager != null) {
            listenerManager.remove(listener);
        }
    }

    /**
     * It creates a new MimeMessage with standard Aspirin ID header.
     *
     * @return new MimeMessage object
     *
     */
    public MimeMessage createNewMimeMessage() {
        if (defaultSession == null) {
            defaultSession = Session.getDefaultInstance(System.getProperties());
        }
        MimeMessage mMesg = new MimeMessage(defaultSession);
        synchronized (idCounterLock) {
            long nowTime = System.currentTimeMillis() / 1000;
            String newId = nowTime + "." + Integer.toHexString(idCounter++);
            try {
                mMesg.setHeader(Aspirin.HEADER_MAIL_ID, newId);
            } catch (MessagingException msge) {
                log.warn("Aspirin Mail ID could not be generated.", msge);
                msge.printStackTrace();
            }
        }
        return mMesg;
    }

    public static Collection<InternetAddress> extractRecipients(MimeMessage message) throws MessagingException {
        Collection<InternetAddress> recipients = new ArrayList<InternetAddress>();

        Address[] addresses;
        Message.RecipientType[] types = new Message.RecipientType[]{
            RecipientType.TO,
            RecipientType.CC,
            RecipientType.BCC
        };
        for (Message.RecipientType recType : types) {
            addresses = message.getRecipients(recType);
            if (addresses != null) {
                for (Address addr : addresses) {
                    try {
                        recipients.add((InternetAddress) addr);
                    } catch (Exception e) {
                        log.warn("Recipient parsing failed.", e);
                    }
                }
            }
        }
        return recipients;
    }





    public DeliveryManager getDeliveryManager() {
        return deliveryManager;
    }

    public ListenerManager getListenerManager() {
        return listenerManager;
    }

    public void shutdown() {
        deliveryManager.shutdown();
    }
}
