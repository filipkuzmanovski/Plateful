package mk.ukim.finki.wp.recipeappbackend.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Chooses the review email sender: SMTP once a mail account is configured (MAIL_USERNAME), the
 * logging stand-in otherwise, so the app starts and works without any mail configuration.
 */
@Configuration
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    public ReviewEmailSender reviewEmailSender(
            @Value("${spring.mail.username:}") String username,
            ObjectProvider<JavaMailSender> mailSender) {
        if (username == null || username.isBlank()) {
            log.info("spring.mail.username is blank: review emails are logged, not sent");
            return new LoggingReviewEmailSender();
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn("spring.mail.host is not set: review emails are logged, not sent");
            return new LoggingReviewEmailSender();
        }
        return new SmtpReviewEmailSender(sender, username);
    }
}
