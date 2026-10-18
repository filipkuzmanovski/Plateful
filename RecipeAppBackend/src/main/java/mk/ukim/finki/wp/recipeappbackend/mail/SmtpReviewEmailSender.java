package mk.ukim.finki.wp.recipeappbackend.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/** Sends plain-text mail over SMTP, From the configured account with the display name "Plateful". */
public class SmtpReviewEmailSender implements ReviewEmailSender {

    private static final String DISPLAY_NAME = "Plateful";

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public SmtpReviewEmailSender(JavaMailSender mailSender, String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(String to, String subject, String body) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress, DISPLAY_NAME);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(message);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailSendException("Could not build the review email", e);
        }
    }
}
