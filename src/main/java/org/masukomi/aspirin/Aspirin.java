package org.masukomi.aspirin;

import java.util.Date;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;

import org.masukomi.aspirin.core.AspirinInternal;
import org.masukomi.aspirin.core.config.Configuration;
import org.masukomi.aspirin.core.listener.AspirinListener;
import org.masukomi.aspirin.core.store.mail.FileMailStore;
import org.masukomi.aspirin.core.store.mail.MailStore;
import org.masukomi.aspirin.core.store.mail.SimpleMailStore;
import org.masukomi.aspirin.core.store.queue.QueueInfo;
import org.masukomi.aspirin.core.store.queue.QueueStore;

/**
 * This is the facade class of the Aspirin package. You should to use this 
 * class to manage email sending.
 * 
 * <h2>How it works?</h2>
 * 
 * <p>All email is represented by two main object:</p>
 * 
 * <p>A {@link MimeMessage}, which contains the RAW content of an email, so it 
 * could be very large. It is stored in a {@link MailStore} (there is two 
 * different implementation in Aspirin - one for simple in-memory usage
 * {@link SimpleMailStore} and one for heavy usage {@link FileMailStore}, this 
 * stores all MimeMessage objects on filesystem.) If no one of these default 
 * stores is good for you, you can implement the MailStore interface.</p>
 * 
 * <p>A QueueInfo {@link QueueInfo}, which represents an email and a 
 * recipient together, so one email could associated to more QueueInfo objects. 
 * This is an inside object, which contains all control informations of a mail 
 * item. In Aspirin package there is a {@link QueueStore} for in-memory use 
 * {@link SimpleQueueStore}, this is the default implementation to store 
 * QueueInfo objects. You can find an additional package, which use SQLite 
 * (based on <a href="http://sqljet.com">SQLJet</a>) to store QueueInfo 
 * object.</p>
 * 
 * <p><b>Hint:</b> If you need a Quality-of-Service mail sending, use
 * {@link FileMailStore} and additional <b>SqliteQueueStore</b>, they could 
 * preserve emails in queue between runs or on Java failure.</p>
 * 
 * @author Laszlo Solova
 *
 */
public class Aspirin {
	
	/**
	 * Name of ID header placed in MimeMessage object. If no such header is 
	 * defined in a MimeMessage, then MimeMessage's toString() method is used 
	 * to generate a new one.
	 */
	public static final String HEADER_MAIL_ID = "X-Aspirin-MailID";
	
	/**
	 * Name of expiration time header placed in MimeMessage object. Default 
	 * expiration time is -1, unlimited. Expiration time is an epoch timestamp 
	 * in milliseconds.
	 */
	public static final String HEADER_EXPIRY = "X-Aspirin-Expiry";
	

}
