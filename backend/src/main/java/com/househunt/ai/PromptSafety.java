package com.househunt.ai;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Helpers for putting untrusted text (pasted listings, user notes) into prompts.
 *
 * <p>Untrusted text is wrapped in a block whose tag carries a random nonce, e.g.
 * {@code <listing-3f9a1c>...</listing-3f9a1c>}, so text inside cannot "close" the block and continue as
 * instructions (it cannot guess the nonce). Anything that looks like one of our tags is removed first, and control
 * characters are dropped. This is defence in depth, not a guarantee; the real guarantees are server-side
 * validation of outputs and read-only tools.
 */
public final class PromptSafety {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t\\r]]");

    private PromptSafety() {
    }

    public static String nonce() {
        var bytes = new byte[3];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Removes control characters and any tag that could be mistaken for one of our delimiters. */
    public static String neutralize(String text, String tagName) {
        if (text == null) return "";
        var s = CONTROL.matcher(text).replaceAll("");
        return s.replaceAll("(?i)</?\\s*" + Pattern.quote(tagName) + "[^>]*>", "");
    }

    /** {@code <tag-nonce>\n...\n</tag-nonce>} with the content neutralized. */
    public static String wrap(String tagName, String nonce, String untrusted) {
        var tag = tagName + "-" + nonce;
        return "<" + tag + ">\n" + neutralize(untrusted, tagName) + "\n</" + tag + ">";
    }
}
