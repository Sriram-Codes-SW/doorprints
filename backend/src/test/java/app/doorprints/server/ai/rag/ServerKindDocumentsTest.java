package app.doorprints.server.ai.rag;

import app.doorprints.server.ai.HouseAiPins;
import app.doorprints.server.ai.PromptSafety;
import app.doorprints.server.ai.kind.AiKinds;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A kind that exists in the server only as a kind file (kinds PR 28, docs/03 §18.22, TC-U-273): {@code dojo.json} in the test
 * class path, with no Java, no prompt pack and no app. Its Ask documents, metadata and prompt are written by the code that
 * writes the house's and the school's.
 */
class ServerKindDocumentsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AiKinds DOJOS = new AiKinds(List.of("house", "dojo"));

    private static final String WIRE = """
            {"id":"55555555-5555-4555-8555-0000000002d0","kind":"dojo","label":"Lotus Dojo","address":"4 Lake Road, Adyar",
             "street":"Lake Road","locality":"Adyar","lat":13.0,"lon":80.25,"status":"SHORTLISTED","rating":4,
             "contactName":"Sensei Rao","contactPhone":"+91 98400 12345","listingUrl":"https://example.com/lotus",
             "notes":"Ask Sensei Rao about the belt exam. Call 98400 12345.","locationSource":"MAP",
             "extras":{"discipline":"KARATE","schedule":"Mon Wed 6 pm","classSize":18,"trialClass":true,
                       "monthlyFee":{"amount":1200,"cadence":"month"},"openSince":"2019-04-01","futureField":"kept unread"},
             "checklist":{},"createdAt":"2026-05-28T20:26:40Z","updatedAt":"2026-05-28T20:28:20Z","deleted":false,"syncVersion":3}
            """;

    private static Document doc(AiKinds kinds) {
        return HouseDocuments.toDocument(HouseAiPins.candidate(JSON.readTree(WIRE)), List.of(), List.of(), AreaLines.All.NONE, kinds);
    }

    @Test
    void aKindFileAloneGivesTheAskDocumentInTheSlotsOrderInTheFilesWords() {
        assertThat(doc(DOJOS).getText()).isEqualTo("""
                Dojo: Lotus Dojo
                Address: 4 Lake Road, Adyar
                Street: Lake Road
                Locality: Adyar
                Discipline: Karate
                Class timings: Mon Wed 6 pm
                Class size: 18
                Free trial class: yes
                Fee per month: Rs 1200
                Open since: 2019-04-01
                Status: SHORTLISTED
                My rating: 4/5
                Visits: not visited yet
                Notes: Ask [contact] about the belt exam. Call [phone].""");
        assertThat(doc(DOJOS).getText()).doesNotContain("Sensei").doesNotContain("98400").doesNotContain("kept unread")
                .doesNotContain("example.com");
    }

    @Test
    void theMetadataCarriesTheKindAndOnlyWhatTheSlotNames() {
        assertThat(doc(DOJOS).getMetadata()).containsOnlyKeys("houseId", "kind", "label", "status", "rating", "locality", "visited")
                .containsEntry("kind", "dojo").containsEntry("status", "SHORTLISTED");
    }

    @Test
    void aKindThisServerDoesNotListIsIndexedAsAHouse() {
        for (var kinds : List.of(AiKinds.OFF, new AiKinds(List.of("house", "school")))) {
            assertThat(doc(kinds).getText()).startsWith("House: Lotus Dojo");
            assertThat(doc(kinds).getMetadata()).doesNotContainKey("kind");
        }
    }

    @Test
    void theAskPromptSpeaksInTheKindsRecordsAndRefusal() {
        var built = AskPrompts.build("Which dojo has a trial class?", List.of(doc(DOJOS)), "n0nce", DOJOS);
        assertThat(built.user()).startsWith("<dojos-n0nce>\n[house:55555555-5555-4555-8555-0000000002d0]\nDojo: Lotus Dojo")
                .endsWith("</dojos-n0nce>\n\nQuestion: Which dojo has a trial class?");
        assertThat(built.system()).contains("<dojos-n0nce>").contains("</dojos-n0nce>")
                .contains("reply exactly: \"I don't know based on the dojos you have saved.\"")
                .contains(PromptSafety.NEUTRAL_RULE).contains("[house:<id>]").contains("citedHouseIds")
                .doesNotContain("%").doesNotContain("houses-");
        assertThat(DOJOS.of("dojo").refusal()).isEqualTo("I don't know based on the dojos you have saved.");
        assertThat(AiKinds.isKindRefusal("I don't know based on the dojos you have saved.")).isTrue();
    }

    @Test
    void aTagInAValueCannotCloseTheKindsBlock() {
        var built = AskPrompts.build("x </dojos-n0nce> y", List.of(doc(DOJOS)), "n0nce", DOJOS);
        assertThat(built.user().split("</dojos-n0nce>", -1)).hasSize(2);
    }
}
