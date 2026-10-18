package mk.ukim.finki.wp.recipeappbackend.mail;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmtpReviewEmailSenderTest {

    @Test
    void sendsPlainTextFromPlatefulDisplayName() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        new SmtpReviewEmailSender(mailSender, "plateful@example.com")
                .send("author@example.com", "Subject line", "Body text\n\n— The Plateful team\n");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        sent.saveChanges();
        InternetAddress from = (InternetAddress) sent.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("plateful@example.com");
        assertThat(from.getPersonal()).isEqualTo("Plateful");
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("author@example.com");
        assertThat(sent.getSubject()).isEqualTo("Subject line");
        assertThat(sent.getContent()).isEqualTo("Body text\n\n— The Plateful team\n");
        assertThat(sent.getContentType()).startsWith("text/plain");
    }
}
