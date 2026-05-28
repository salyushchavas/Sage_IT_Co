package com.spire.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for the Terms of Service content. Reads
 * {@code resources/terms/{version}.json} and exposes the parsed
 * structure to both the public {@code /api/agreement/terms} endpoint
 * and the personalized signed-PDF generator. Both paths render the
 * same JSON so the email PDF and the website are always identical.
 *
 * Versions are immutable — once a JSON file ships in a release we
 * never edit it; bumping requires a new {@code vN.M.json} so historic
 * acceptance rows (which stamp the version they accepted) remain
 * verifiable against their original wording.
 *
 * Loaded eagerly at boot and cached in memory; the file is small and
 * only changes between releases.
 */
@Service
@Slf4j
public class TermsContentService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, TermsDocument> cache = new ConcurrentHashMap<>();

    /** Eagerly load the current version so a malformed JSON fails on boot, not at first request. */
    @PostConstruct
    void warmup() {
        try {
            getTerms(AgreementService.CURRENT_VERSION);
            log.info("Terms content loaded: {}", AgreementService.CURRENT_VERSION);
        } catch (Exception e) {
            log.error("Failed to load terms content {}: {}",
                    AgreementService.CURRENT_VERSION, e.getMessage());
        }
    }

    /**
     * Returns the parsed terms doc for a given version. Caches the
     * result; returns the cached instance on subsequent calls.
     *
     * @throws IllegalStateException if the version file is missing or unreadable
     */
    public TermsDocument getTerms(String version) {
        return cache.computeIfAbsent(version, this::load);
    }

    private TermsDocument load(String version) {
        String path = "terms/" + version + ".json";
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("Terms file not found on classpath: " + path);
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(in);

            List<Section> sections = new ArrayList<>();
            for (JsonNode s : root.path("sections")) {
                sections.add(new Section(
                        s.path("title").asText(""),
                        s.path("content").asText("")));
            }

            List<String> confirmations = new ArrayList<>();
            for (JsonNode c : root.path("acceptanceConfirmations")) {
                confirmations.add(c.asText(""));
            }

            return new TermsDocument(
                    root.path("version").asText(version),
                    root.path("effectiveDate").asText(""),
                    root.path("lastUpdated").asText(""),
                    root.path("platform").asText("Spire Info Tech"),
                    root.path("platformUrl").asText(""),
                    root.path("contactEmail").asText(""),
                    root.path("supportUrl").asText(""),
                    root.path("jurisdiction").asText(""),
                    List.copyOf(sections),
                    List.copyOf(confirmations));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read terms file " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * Shape returned to the public API endpoint — uses LinkedHashMap
     * so the field order matches the JSON file order in the response.
     */
    public Map<String, Object> toApiResponse(TermsDocument doc) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", doc.version());
        out.put("lastUpdated", doc.lastUpdated());
        out.put("effectiveDate", doc.effectiveDate());
        out.put("platform", doc.platform());

        List<Map<String, String>> sections = new ArrayList<>(doc.sections().size());
        for (Section s : doc.sections()) {
            sections.add(Map.of("title", s.title(), "content", s.content()));
        }
        out.put("sections", sections);
        out.put("acceptanceConfirmations", doc.acceptanceConfirmations());
        return out;
    }

    public record TermsDocument(
            String version,
            String effectiveDate,
            String lastUpdated,
            String platform,
            String platformUrl,
            String contactEmail,
            String supportUrl,
            String jurisdiction,
            List<Section> sections,
            List<String> acceptanceConfirmations
    ) {}

    public record Section(String title, String content) {}
}
