/*
 * Copyright 2012 brad.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.masukomi.aspirin.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import org.masukomi.aspirin.Aspirin;
import org.masukomi.aspirin.core.config.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author brad
 */
public class Helper {
    private static final Logger log = LoggerFactory.getLogger(Helper.class);
    
    /**
     * Formatter to set expiry header. Please, use this formatter to create or
     * change a current header.
     */
    public final SimpleDateFormat expiryFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
    
    
    private final Configuration configuration;

    public Helper(Configuration configuration) {
        this.configuration = configuration;
    }
    
    /**
     * Format expiry header content.
     *
     * @param date Expiry date of a message.
     * @return Formatted date of expiry - as String. It could be add as
     * MimeMessage header. Please use HEADER_EXPIRY constant as header name.
     */
    public String formatExpiry(Date date) {
        return expiryFormat.format(date);
    }    
    

    /**
     * It gives back expiry value of a message in epoch milliseconds.
     *
     * @param message The MimeMessage which expiry is needed.
     * @return Expiry in milliseconds.
     */
    public long getExpiry(MimeMessage message) {
        String headers[];
        try {
            headers = message.getHeader(Aspirin.HEADER_EXPIRY);
            if (headers != null && 0 < headers.length) {
                return expiryFormat.parse(headers[0]).getTime();
            }
        } catch (Exception e) {
            log.error("Expiration header could not be get from MimeMessage.", e);
        }
        if (configuration.getExpiry() == Configuration.NEVER_EXPIRES) {
            return Long.MAX_VALUE;
        }
        try {
            Date sentDate = message.getReceivedDate();
            if (sentDate != null) {
                return sentDate.getTime() + configuration.getExpiry();
            }
        } catch (MessagingException e) {
            log.error("Expiration calculation could not be based on message date.", e);
        }
        return System.currentTimeMillis() + configuration.getExpiry();
    }

    public void setExpiry(MimeMessage message, long expiry) {
        try {
            message.setHeader(Aspirin.HEADER_EXPIRY, expiryFormat.format(new Date(System.currentTimeMillis() + expiry)));
        } catch (MessagingException e) {
            log.error("Could not set Expiry of the MimeMessage: " + getMailID(message) + ".", e);
        }
    }    
    

    /**
     * Decode mail ID from MimeMessage. If no such header was defined, then we
     * get MimeMessage's toString() method result back.
     *
     * @param message MimeMessage, which ID needs.
     * @return An unique mail id associated to this MimeMessage.
     */
    public String getMailID(MimeMessage message) {
        String[] headers;
        try {
            headers = message.getHeader(Aspirin.HEADER_MAIL_ID);
            if (headers != null && 0 < headers.length) {
                return headers[0];
            }
        } catch (MessagingException e) {
            log.error("MailID header could not be get from MimeMessage.", e);
        }
        return message.toString();
    }    
}
