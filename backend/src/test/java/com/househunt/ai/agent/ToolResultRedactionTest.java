package com.househunt.ai.agent;

import com.househunt.ai.mcp.McpHouseTools;
import com.househunt.ai.rag.RagService;
import com.househunt.house.HouseDto;
import com.househunt.house.HouseService;
import com.househunt.house.HouseStatus;
import com.househunt.visit.VisitRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F-30: agent tool results (VisitPlannerTools) and MCP tool results (McpHouseTools) are provider-bound; they must never
 * carry the contact name or phone. Real HouseQueries/HouseSearchService, only the house service is mocked.
 */
class ToolResultRedactionTest {

    private static final String NAME = "Lakshmi Narayanan";
    private static final String PHONE = "+91 99001 23456";

    private final UUID id = UUID.randomUUID();
    private final HouseDto house = new HouseDto(id, "Lakshmi Narayanan house", "4th cross, call 99001-23456",
            "4th Cross", "Jayanagar", 12.93, 77.58, HouseStatus.SHORTLISTED, 32000L, "RENT", 2, 5, NAME, PHONE,
            "https://example.com/l/1", "Lakshmi showed us around. Narayanan sir wants 6 months deposit. 9900123456",
            Map.of("water", 4), null, null, false, 3, 120.0);

    private final HouseService houseService = mock(HouseService.class);
    private final HouseQueries queries = new HouseQueries(new HouseSearchService(houseService),
            mock(VisitRepository.class));

    private void stub() {
        when(houseService.list(any())).thenReturn(List.of(house));
        when(houseService.get(id)).thenReturn(house);
        when(houseService.nearby(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(house));
    }

    private static void assertNoContact(Object toolResult) {
        // Record toString() prints every component, so this covers every field the tool serialises.
        assertThat(String.valueOf(toolResult))
                .doesNotContainIgnoringCase("Lakshmi")
                .doesNotContainIgnoringCase("Narayanan")
                .doesNotContain("99001").doesNotContain("23456").doesNotContain("9900123456");
    }

    @Test
    void agentToolResultsCarryNoContact() {
        stub();
        var tools = new VisitPlannerTools(queries, 12.93, 77.58);

        assertNoContact(tools.searchHouses("deposit", null, null, null, null, null, null));
        assertNoContact(tools.nearbyHouses(12.93, 77.58, 500.0));
        var details = tools.houseDetails(id.toString());
        assertNoContact(details);
        assertThat(details.notes()).isEqualTo("[contact] showed us around. [contact] sir wants 6 months deposit. [phone]");
        assertThat(details.label()).isEqualTo("[contact] house");
        assertThat(details.address()).isEqualTo("4th cross, call [phone]");
        assertThat(details.price()).isEqualTo(32000L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mcpToolResultsCarryNoContact() {
        stub();
        var tools = new McpHouseTools(queries, (ObjectProvider<RagService>) mock(ObjectProvider.class));

        assertNoContact(tools.searchHouses(null, "SHORTLISTED", null, null, null, null, null));
        assertNoContact(tools.nearbyHouses(12.93, 77.58, null));
        assertNoContact(tools.houseDetails(id.toString()));
    }

    @Test
    void searchCannotConfirmAGuessedContactNameOrPhone() {
        stub();
        // The text filter matches the redacted text (the same text the model sees), so it is no oracle.
        assertThat(queries.searchHouses("lakshmi", null, null, null, null, null, null)).isEmpty();
        assertThat(queries.searchHouses("Narayanan", null, null, null, null, null, null)).isEmpty();
        assertThat(queries.searchHouses("99001", null, null, null, null, null, null)).isEmpty();
        var found = queries.searchHouses("deposit", null, null, null, null, null, null);
        assertThat(found).hasSize(1);
        assertNoContact(found);
    }

    @Test
    void labelChecklistAndUrlNamedAfterTheOwnersFirstNameLoseItInEveryToolResult() {
        var ramesh = new HouseDto(id, "Ramesh's 2BHK", "12 MG Road", "MG Road", "Indiranagar", 12.97, 77.64,
                HouseStatus.SHORTLISTED, 28000L, "RENT", 2, 4, "Ramesh Kumar", "+91 98450 12345",
                "https://example.com/rent/ramesh-2bhk", "Nice", Map.of("Ramesh fixes leaks", 4), null, null, false, 7,
                80.0);
        when(houseService.list(any())).thenReturn(List.of(ramesh));
        when(houseService.get(id)).thenReturn(ramesh);
        when(houseService.nearby(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(ramesh));

        var summaries = queries.searchHouses(null, null, null, null, null, null, null);
        assertThat(summaries).singleElement().satisfies(s -> assertThat(s.label()).isEqualTo("[contact]'s 2BHK"));
        var details = HouseQueries.HouseDetails.of(ramesh);
        assertThat(details.label()).isEqualTo("[contact]'s 2BHK");
        assertThat(details.checklist()).containsOnlyKeys("[contact] fixes leaks");
        assertThat(details.listingUrl()).isEqualTo("https://example.com/rent/[contact]-2bhk");

        var tools = new VisitPlannerTools(queries, 12.97, 77.64);
        var results = List.<Object>of(summaries, tools.nearbyHouses(12.97, 77.64, 500.0),
                tools.houseDetails(id.toString()));
        for (var result : results) {
            assertThat(String.valueOf(result)).doesNotContainIgnoringCase("Ramesh").doesNotContain("98450");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void careOfAddressWithTheOwnersFullNameLosesItInEveryToolResult() {
        var careOf = new HouseDto(id, "Blue gate", "C/o Ramesh Kumar, 12 MG Road", "C/o Ramesh  Kumar",
                "Kumar Ramesh layout", 12.97, 77.64, HouseStatus.SHORTLISTED, 28000L, "RENT", 2, 4,
                "Mr. Ramesh Kumar", "+91 98450 12345", null, "Nice", Map.of("water", 4), null, null, false, 7, 80.0);
        when(houseService.list(any())).thenReturn(List.of(careOf));
        when(houseService.get(id)).thenReturn(careOf);
        when(houseService.nearby(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(careOf));

        var details = HouseQueries.HouseDetails.of(careOf);
        assertThat(details.address()).isEqualTo("C/o [contact], 12 MG Road");
        assertThat(details.street()).isEqualTo("C/o [contact]");
        assertThat(details.locality()).isEqualTo("[contact] layout");
        var summaries = queries.searchHouses(null, null, null, null, null, null, null);
        assertThat(summaries).singleElement().satisfies(s -> {
            assertThat(s.street()).isEqualTo("C/o [contact]");
            assertThat(s.locality()).isEqualTo("[contact] layout");
        });

        var agent = new VisitPlannerTools(queries, 12.97, 77.64);
        var mcp = new McpHouseTools(queries, (ObjectProvider<RagService>) mock(ObjectProvider.class));
        var results = List.<Object>of(summaries, details,
                agent.searchHouses(null, null, null, null, null, null, null), agent.nearbyHouses(12.97, 77.64, 500.0),
                agent.houseDetails(id.toString()),
                mcp.searchHouses(null, "SHORTLISTED", null, null, null, null, null), mcp.nearbyHouses(12.97, 77.64, null),
                mcp.houseDetails(id.toString()));
        for (var result : results) {
            assertThat(String.valueOf(result)).doesNotContainIgnoringCase("Ramesh Kumar")
                    .doesNotContainIgnoringCase("Kumar Ramesh").doesNotContainIgnoringCase("Ramesh  Kumar")
                    .doesNotContain("98450");
        }
    }
}
