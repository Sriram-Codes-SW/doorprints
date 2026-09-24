package app.doorprints.server.ai.web;

import app.doorprints.server.ai.agent.PlanModels.PlanRequest;
import app.doorprints.server.ai.agent.PlanModels.PlanResponse;
import app.doorprints.server.ai.agent.VisitPlannerService;
import app.doorprints.server.ai.extract.HouseDraft;
import app.doorprints.server.ai.extract.ListingExtractionService;
import app.doorprints.server.ai.rag.AskModels.AskRequest;
import app.doorprints.server.ai.rag.AskModels.AskResponse;
import app.doorprints.server.ai.rag.HouseIndexer;
import app.doorprints.server.ai.rag.RagService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * /api/ai/** — only exists with {@code app.ai.enabled=true} (otherwise these paths are 404 and
 * {@code GET /api/ai/status} reports {@code enabled:false}). Protected by the API-key filter and the AI rate limit.
 */
@RestController
@RequestMapping("/api/ai")
@ConditionalOnBooleanProperty("app.ai.enabled")
public class AiController {

    /** Hard cap for the JSON body field; the configurable, smaller limit is enforced in the service. */
    public record ExtractRequest(@NotBlank @Size(max = 20000) String text) {
    }

    private final ListingExtractionService extraction;
    private final RagService rag;
    private final HouseIndexer indexer;
    private final VisitPlannerService planner;

    public AiController(ListingExtractionService extraction, RagService rag, HouseIndexer indexer,
                        VisitPlannerService planner) {
        this.extraction = extraction;
        this.rag = rag;
        this.indexer = indexer;
        this.planner = planner;
    }

    @PostMapping("/extract-listing")
    public HouseDraft extractListing(@Valid @RequestBody ExtractRequest body) {
        return extraction.extract(body.text());
    }

    @PostMapping("/ask")
    public AskResponse ask(@Valid @RequestBody AskRequest body) {
        return rag.ask(body.question(), body.filters());
    }

    @PostMapping("/plan-visits")
    public PlanResponse planVisits(@Valid @RequestBody PlanRequest body) {
        return planner.plan(body);
    }

    @PostMapping("/reindex")
    public Map<String, Integer> reindex() {
        try {
            return Map.of("indexed", indexer.reindexAll());
        } catch (RuntimeException e) {
            throw new AiUnavailableException("Re-indexing failed", e);
        }
    }
}
