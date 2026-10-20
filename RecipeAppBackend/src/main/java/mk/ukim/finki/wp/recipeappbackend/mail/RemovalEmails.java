package mk.ukim.finki.wp.recipeappbackend.mail;

/** The exact author-facing copy for admin removals. Pure text, no I/O. */
final class RemovalEmails {

    static final int MAX_EXCERPT = 200;

    private RemovalEmails() {
    }

    static ReviewEmails.Email recipeRemoved(String title, String reason) {
        return new ReviewEmails.Email(
                "Your recipe \"" + ReviewEmails.withoutLineBreaks(title) + "\" was removed from Plateful",
                "Your recipe \"" + title + "\" was removed from Plateful by an admin.\n"
                        + "\n"
                        + "Reason: " + reason + "\n"
                        + "\n"
                        + "If you think this was a mistake, reply to this email.\n"
                        + "\n"
                        + ReviewEmails.SIGN_OFF);
    }

    static ReviewEmails.Email commentRemoved(String recipeTitle, String reason, String commentText) {
        return new ReviewEmails.Email(
                "Your comment on \"" + ReviewEmails.withoutLineBreaks(recipeTitle) + "\" was removed",
                "Your comment on \"" + recipeTitle + "\" was removed from Plateful by an admin.\n"
                        + "\n"
                        + "Reason: " + reason + "\n"
                        + "\n"
                        + "Your comment: \"" + excerpt(commentText) + "\"\n"
                        + "\n"
                        + "If you think this was a mistake, reply to this email.\n"
                        + "\n"
                        + ReviewEmails.SIGN_OFF);
    }

    /** The first 200 characters (whole code points), with an ellipsis when something was cut. */
    private static String excerpt(String text) {
        if (text.codePointCount(0, text.length()) <= MAX_EXCERPT) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, MAX_EXCERPT)) + "…";
    }
}
