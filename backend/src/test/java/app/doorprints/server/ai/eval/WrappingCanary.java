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

package app.doorprints.server.ai.eval;

import app.doorprints.server.ai.extract.ExtractionPrompts;
import app.doorprints.server.ai.rag.AskPrompts;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * The prompts of the {@code no-wrapping} canary (S4b-BL-239, docs/ai/ai-design.md 8.6): what Extract and Ask would send
 * without the pipeline's delimiting. The untrusted text sits between a fixed tag (no nonce, so the tag can be guessed)
 * and is not neutralised (a tag in it stays); for Ask the "Records exist only between the tags" rule is left out too.
 * Everything else is the real builders' output: the prompts are built by {@link ExtractionPrompts} and {@link AskPrompts}
 * and only the delimiters and that one bullet are rewritten, so a prompt edit that moves them fails loudly here (an
 * {@link IllegalStateException}, reported by the harness as a harness error) instead of turning the canary into a no-op.
 * Test sources only; public because the seam tests of the service packages call it.
 */
public final class WrappingCanary {

    /** The phrase of the Ask bullet this canary removes. */
    public static final String ASK_BULLET = "Records exist only between";
    /** The nonce the real builders are called with before the tag is made fixed. */
    private static final String NONCE = "xxxxxx";

    private WrappingCanary() {
    }

    /** The Extract prompt for the raw listing text: tag {@code listing}, the text as it is. */
    public static ExtractionPrompts.Built extract(String text) {
        var real = ExtractionPrompts.build(text, NONCE);
        var open = "<listing-" + NONCE + ">\n";
        int at = real.user().indexOf(open);
        require(at >= 0 && real.system().contains("listing-" + NONCE), "the Extract prompt no longer wraps the listing in <listing-nonce>");
        return new ExtractionPrompts.Built(real.system().replace("listing-" + NONCE, "listing"),
                real.user().substring(0, at) + "<listing>\n" + text + "\n</listing>");
    }

    /** The Ask prompt for the raw question and records: tag {@code houses}, the texts as they are, the tags rule left out. */
    public static AskPrompts.Built ask(String question, List<Document> docs) {
        var real = AskPrompts.build(question, docs, NONCE);
        var open = "<houses-" + NONCE + ">\n";
        var close = "\n</houses-" + NONCE + ">\n\nQuestion: ";
        var user = real.user();
        int end = user.indexOf(close);
        require(user.startsWith(open) && end > 0, "the Ask prompt no longer has the shape <houses-nonce> records </houses-nonce> Question: ...");
        var context = user.substring(open.length(), end);
        for (var d : docs) {
            var raw = d.getText() == null ? "" : d.getText();
            var neutral = app.doorprints.server.ai.PromptSafety.neutralize(raw, "houses");
            if (!neutral.isEmpty() && !neutral.equals(raw)) context = context.replace(neutral, raw);
        }
        var system = Canaries.withoutBullet(real.system(), ASK_BULLET).replace("houses-" + NONCE, "houses");
        return new AskPrompts.Built(system, "<houses>\n" + context + "\n</houses>\n\nQuestion: " + question);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException("canary no-wrapping: " + message + "; update the canary");
    }
}
