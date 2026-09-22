package com.househunt.ai.rag;

import com.househunt.ai.rag.AskModels.ModelAnswer;
import com.househunt.house.House;
import com.househunt.house.HouseDto;
import com.househunt.house.HouseStatus;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-30: the Ask prompt (DF-21) and the citations (returned to MCP clients too) never contain the contact name or
 * phone, including for chunks indexed before the fix, which still hold a "Contact:" line.
 */
class AskContextRedactionTest {

    private final UUID id = UUID.randomUUID();

    private House house() {
        var h = new House(id);
        h.setLabel("Blue gate");
        h.setContactName("Ramesh Kumar");
        h.setContactPhone("+91 98450 12345");
        return h;
    }

    /** What the vector store returns for a house indexed by the pre-fix HouseDocuments. */
    private Document legacyChunk() {
        var text = """
                House: Blue gate, Ramesh Kumar's building
                Locality: Indiranagar
                Price: Rs 28000 per month (rent)
                Contact: Ramesh Kumar
                Notes: Ramesh says water is 24x7. His number 98450 12345.""";
        return Document.builder().id(id.toString()).text(text)
                .metadata(Map.of("houseId", id.toString(), "label", "Ramesh Kumar's building", "status", "NEW"))
                .score(0.8).build();
    }

    @Test
    void legacyChunksAreScrubbedBeforeThePromptAndCitations() {
        var safe = RagService.redacted(List.of(legacyChunk()), Map.of(id.toString(), house()));

        var prompt = AskPrompts.build("Which house has 24x7 water?", safe, "abc123");
        for (var provider : List.of(prompt.system(), prompt.user())) {
            assertThat(provider).doesNotContainIgnoringCase("Ramesh").doesNotContain("98450").doesNotContain("Contact:");
        }
        assertThat(prompt.user()).contains("[house:" + id + "]").contains("Notes: [contact] says water is 24x7. "
                + "His number [phone].");

        var answer = new ModelAnswer("The Blue gate house [house:" + id + "].", List.of(id.toString()));
        var citations = RagService.citations(answer, safe, "Which house has 24x7 water?");
        assertThat(citations).singleElement().satisfies(c -> {
            assertThat(c.label()).isEqualTo("[contact]'s building");
            assertThat(c.snippet()).doesNotContainIgnoringCase("Ramesh").doesNotContain("98450");
        });
        assertThat(safe.getFirst().getScore()).isEqualTo(0.8);
        assertThat(safe.getFirst().getMetadata()).containsEntry("status", "NEW");
    }

    @Test
    void citationLabelNamedAfterTheOwnersFirstNameLosesIt() {
        var chunk = Document.builder().id(id.toString()).text("House: Ramesh's 2BHK\nLocality: Indiranagar")
                .metadata(Map.of("houseId", id.toString(), "label", "Ramesh's 2BHK")).score(0.7).build();
        var safe = RagService.redacted(List.of(chunk), Map.of(id.toString(), house()));

        var prompt = AskPrompts.build("Which 2BHK is in Indiranagar?", safe, "abc123");
        assertThat(prompt.system() + prompt.user()).doesNotContainIgnoringCase("Ramesh");
        var answer = new ModelAnswer("That one [house:" + id + "].", List.of(id.toString()));
        assertThat(RagService.citations(answer, safe, "q")).singleElement().satisfies(c -> {
            assertThat(c.label()).isEqualTo("[contact]'s 2BHK");
            assertThat(c.snippet()).doesNotContainIgnoringCase("Ramesh");
        });
        assertThat(safe.getFirst().getText()).isEqualTo("House: [contact]'s 2BHK\nLocality: Indiranagar");
    }

    @Test
    void houseDeletedMeanwhileStillLosesTheContactLineAndPhones() {
        var safe = RagService.redacted(List.of(legacyChunk()), Map.of());
        assertThat(safe.getFirst().getText()).doesNotContain("Contact:").doesNotContain("98450");
    }

    @Test
    void freshlyIndexedChunksGoThroughUnchangedApartFromRedaction() {
        var dto = new HouseDto(id, "Blue gate", null, null, "Indiranagar", 12.97, 77.64, HouseStatus.NEW, 28000L,
                "RENT", 2, null, "Ramesh Kumar", "+91 98450 12345", null, "Ramesh says water is 24x7",
                Map.of(), null, null, false, 1, null);
        var fresh = HouseDocuments.toDocument(dto, List.of());
        var safe = RagService.redacted(List.of(fresh), Map.of(id.toString(), house()));
        assertThat(safe.getFirst().getText()).isEqualTo(fresh.getText());
        assertThat(fresh.getText()).contains("Notes: [contact] says water is 24x7");
    }
}
