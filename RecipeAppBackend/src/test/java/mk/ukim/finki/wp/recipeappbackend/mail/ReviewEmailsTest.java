package mk.ukim.finki.wp.recipeappbackend.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewEmailsTest {

    @Test
    void approvedTextIsExact() {
        ReviewEmails.Email email = ReviewEmails.approved("Lemon Garlic Shrimp", "http://localhost:5173/recipes/abc");

        assertThat(email.subject()).isEqualTo("Your recipe \"Lemon Garlic Shrimp\" is live on Plateful");
        assertThat(email.body()).isEqualTo(
                "Congratulations! Your recipe \"Lemon Garlic Shrimp\" has been approved and is now live on Plateful.\n"
                        + "\n"
                        + "View it: http://localhost:5173/recipes/abc\n"
                        + "\n"
                        + "— The Plateful team");
    }

    @Test
    void rejectedTextIsExact() {
        ReviewEmails.Email email = ReviewEmails.rejected(
                "Lemon Garlic Shrimp", "Photo is blurry", "http://localhost:5173/recipes/abc/edit");

        assertThat(email.subject()).isEqualTo("Your recipe \"Lemon Garlic Shrimp\" needs changes");
        assertThat(email.body()).isEqualTo(
                "Thanks for sharing \"Lemon Garlic Shrimp\" on Plateful. An admin reviewed it and couldn't approve it yet.\n"
                        + "\n"
                        + "Reason: Photo is blurry\n"
                        + "\n"
                        + "You can edit the recipe and resubmit it here: http://localhost:5173/recipes/abc/edit\n"
                        + "\n"
                        + "— The Plateful team");
    }

    @Test
    void subjectStripsLineBreaks() {
        String title = "A\r\nBcc: x@y";

        assertThat(ReviewEmails.approved(title, "l").subject())
                .doesNotContain("\r").doesNotContain("\n").contains("ABcc: x@y");
        assertThat(ReviewEmails.rejected(title, "why", "l").subject())
                .doesNotContain("\r").doesNotContain("\n").contains("ABcc: x@y");
    }

    @Test
    void reasonLineBreaksStayInBodyOnly() {
        ReviewEmails.Email email = ReviewEmails.rejected("Soup", "Line one\nLine two", "l");

        assertThat(email.body()).contains("Reason: Line one\nLine two\n");
        assertThat(email.subject()).doesNotContain("Line").doesNotContain("\n");
    }
}
