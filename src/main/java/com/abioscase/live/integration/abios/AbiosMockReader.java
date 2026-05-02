package com.abioscase.live.integration.abios;

import com.abioscase.live.integration.abios.exception.AbiosUpstreamTransientException;
import com.abioscase.live.integration.abios.model.AbiosRosterNode;
import com.abioscase.live.integration.abios.model.AbiosEnrichedDocument;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AbiosMockReader {

    private final ObjectMapper objectMapper;

    public List<AbiosRosterNode> readMockRosters() {
        ClassPathResource resource = new ClassPathResource("abios-mock/rosters.json");
        if (!resource.exists()) {
            return List.of();
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            if (root.has("rosters")) {
                return objectMapper.convertValue(root.get("rosters"), new TypeReference<List<AbiosRosterNode>>() {});
            }
            return List.of();
        } catch (IOException e) {
            log.warn("Mock rosters load failed: {}", e.getMessage());
            return List.of();
        }
    }

    public AbiosEnrichedDocument readMock() {
        ClassPathResource resource = new ClassPathResource("abios-mock/series-live.json");
        if (resource.exists()) {
            try (InputStream in = resource.getInputStream()) {
                return objectMapper.readValue(in, AbiosEnrichedDocument.class);
            } catch (IOException e) {
                throw new AbiosUpstreamTransientException("Mock load failed: " + e.getMessage(), e);
            }
        }

        String fallbackFile = System.getenv().getOrDefault("ABIOS_MOCK_FILE", "/app/abios-mock/series-live.json");
        Path path = Path.of(fallbackFile);
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                return objectMapper.readValue(in, AbiosEnrichedDocument.class);
            } catch (IOException e) {
                throw new AbiosUpstreamTransientException("Mock load failed from " + fallbackFile + ": " + e.getMessage(), e);
            }
        }
        throw new AbiosUpstreamTransientException(
                "Mock load failed: resource abios-mock/series-live.json not found and fallback missing at " + fallbackFile);
    }
}
