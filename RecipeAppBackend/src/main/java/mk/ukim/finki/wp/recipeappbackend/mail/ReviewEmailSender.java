package mk.ukim.finki.wp.recipeappbackend.mail;

/** Delivers one plain-text email. Implementations may throw; the caller decides what a failure means. */
public interface ReviewEmailSender {

    void send(String to, String subject, String body);
}
