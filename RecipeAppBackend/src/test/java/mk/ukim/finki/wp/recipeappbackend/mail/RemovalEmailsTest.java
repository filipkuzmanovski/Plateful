package mk.ukim.finki.wp.recipeappbackend.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RemovalEmailsTest {

    @Test
    void recipeRemovedTextIsExact() {
        ReviewEmails.Email email = RemovalEmails.recipeRemoved("Lemon Garlic Shrimp", "Contains spam links");

        assertThat(email.subject()).isEqualTo("Your recipe \"Lemon Garlic Shrimp\" was removed from Plateful");
        assertThat(email.body()).isEqualTo(
                "Your recipe \"Lemon Garlic Shrimp\" was removed from Plateful by an admin.\n"
                        + "\n"
                        + "Reason: Contains spam links\n"
                        + "\n"
                        + "If you think this was a mistake, reply to this email.\n"
                        + "\n"
                        + "— The Plateful team");
    }

    @Test
    void commentRemovedTextIsExact() {
        ReviewEmails.Email email = RemovalEmails.commentRemoved("Lemon Garlic Shrimp", "Be kind", "You are wrong");

        assertThat(email.subject()).isEqualTo("Your comment on \"Lemon Garlic Shrimp\" was removed");
        assertThat(email.body()).isEqualTo(
                "Your comment on \"Lemon Garlic Shrimp\" was removed from Plateful by an admin.\n"
                        + "\n"
                        + "Reason: Be kind\n"
                        + "\n"
                        + "Your comment: \"You are wrong\"\n"
                        + "\n"
                        + "If you think this was a mistake, reply to this email.\n"
                        + "\n"
                        + "— The Plateful team");
    }

    @Test
    void subjectsStripLineBreaksFromTheTitle() {
        String title = "A\r\nBcc: x@y";

        assertThat(RemovalEmails.recipeRemoved(title, "r").subject())
                .isEqualTo("Your recipe \"ABcc: x@y\" was removed from Plateful");
        assertThat(RemovalEmails.commentRemoved(title, "r", "c").subject())
                .isEqualTo("Your comment on \"ABcc: x@y\" was removed");
    }

    @Test
    void commentOfExactly200CharactersIsQuotedInFull() {
        String text = "a".repeat(200);

        assertThat(RemovalEmails.commentRemoved("Soup", "r", text).body())
                .contains("Your comment: \"" + text + "\"\n");
    }

    @Test
    void longerCommentIsCutAt200CharactersWithEllipsis() {
        String text = "a".repeat(200) + "TAIL";

        String body = RemovalEmails.commentRemoved("Soup", "r", text).body();

        assertThat(body).contains("Your comment: \"" + "a".repeat(200) + "…\"\n");
        assertThat(body).doesNotContain("TAIL");
    }

    @Test
    void truncationNeverSplitsASurrogatePair() {
        String text = "a".repeat(199) + "🍲🍲"; // 199 chars + two emoji (one code point each)

        String body = RemovalEmails.commentRemoved("Soup", "r", text).body();

        assertThat(body).contains("Your comment: \"" + "a".repeat(199) + "🍲…\"\n");
    }
}
