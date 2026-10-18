package mk.ukim.finki.wp.recipeappbackend.mail;

/** The exact author-facing copy for review decisions (spec section 4.6). Pure text, no I/O. */
final class ReviewEmails {

    record Email(String subject, String body) {
    }

    private static final String SIGN_OFF = "— The Plateful team";

    private ReviewEmails() {
    }

    static Email approved(String title, String link) {
        String subjectTitle = withoutLineBreaks(title);
        return new Email(
                "Your recipe \"" + subjectTitle + "\" is live on Plateful",
                "Congratulations! Your recipe \"" + title + "\" has been approved and is now live on Plateful.\n"
                        + "\n"
                        + "View it: " + link + "\n"
                        + "\n"
                        + SIGN_OFF);
    }

    static Email rejected(String title, String reason, String link) {
        String subjectTitle = withoutLineBreaks(title);
        return new Email(
                "Your recipe \"" + subjectTitle + "\" needs changes",
                "Thanks for sharing \"" + title + "\" on Plateful. An admin reviewed it and couldn't approve it yet.\n"
                        + "\n"
                        + "Reason: " + reason + "\n"
                        + "\n"
                        + "You can edit the recipe and resubmit it here: " + link + "\n"
                        + "\n"
                        + SIGN_OFF);
    }

    /** Header-injection guard: a CR or LF in a subject would let a title add mail headers. */
    private static String withoutLineBreaks(String text) {
        return text.replace("\r", "").replace("\n", "");
    }
}
