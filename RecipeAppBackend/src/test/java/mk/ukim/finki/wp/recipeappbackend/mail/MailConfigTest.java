package mk.ukim.finki.wp.recipeappbackend.mail;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The blank case is also the startup-safety check: a backend with no MAIL_* variables must still
 * boot, using the logging sender.
 */
class MailConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withUserConfiguration(MailConfig.class)
            .withPropertyValues("spring.mail.host=smtp.example.com", "spring.mail.port=587");

    @Test
    void blankUsernameUsesLoggingSender() {
        runner.withPropertyValues("spring.mail.username=", "spring.mail.password=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ReviewEmailSender.class)).isInstanceOf(LoggingReviewEmailSender.class);
                });
    }

    @Test
    void missingUsernamePropertyUsesLoggingSender() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ReviewEmailSender.class)).isInstanceOf(LoggingReviewEmailSender.class);
        });
    }

    @Test
    void usernameUsesSmtpSender() {
        runner.withPropertyValues("spring.mail.username=plateful@example.com", "spring.mail.password=pw")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ReviewEmailSender.class)).isInstanceOf(SmtpReviewEmailSender.class);
                });
    }
}
