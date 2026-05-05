package com.abioscase.live.integration.abios;

import com.abioscase.live.config.AbiosProperties;
import com.abioscase.live.integration.abios.model.AbiosEnrichedDocument;
import com.abioscase.live.integration.abios.model.AbiosSeriesNode;
import com.abioscase.live.integration.abios.model.AbiosTeamNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AbiosResponseParser {

    private static final int MAX_RESPONSE_BODY_LOG_CHARS = 512_000;

    private final ObjectMapper objectMapper;
    private final AbiosProperties abios;

    public AbiosEnrichedDocument parseSeriesPayload(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) {
            return new AbiosEnrichedDocument();
        }
        JsonNode root = objectMapper.readTree(payload);
        AbiosEnrichedDocument doc;
        if (root.isArray()) {
            List<AbiosSeriesNode> list = objectMapper.convertValue(root, new TypeReference<>() {});
            doc = new AbiosEnrichedDocument();
            doc.setSeries(list);
        } else {
            doc = objectMapper.readValue(payload, AbiosEnrichedDocument.class);
        }
        logParsedSeriesShape(doc);
        return doc;
    }

    /**
     * Atlas list endpoints return either a raw JSON array or an envelope {@code { "data": [ ... ] }}.
     */
    public <T> List<T> parseAtlasListPayload(byte[] payload, String pathForLog, TypeReference<List<T>> typeRef)
            throws IOException {
        if (payload == null || payload.length == 0) {
            return List.of();
        }
        JsonNode root = objectMapper.readTree(payload);
        JsonNode arrayNode = root.isArray() ? root : root.path("data");
        if (!arrayNode.isArray()) {
            log.warn("Abios batch response from {} is not a JSON array and has no data[] array", pathForLog);
            return List.of();
        }
        return objectMapper.convertValue(arrayNode, typeRef);
    }

    public void logRawUpstreamResponse(byte[] payload) {
        if (!abios.isLogUpstreamPayload() && !log.isDebugEnabled()) {
            return;
        }
        if (payload == null || payload.length == 0) {
            String msg = "Abios upstream response: empty body";
            if (abios.isLogUpstreamPayload()) log.info(msg);
            else log.debug(msg);
            return;
        }
        String body = new String(payload, StandardCharsets.UTF_8);
        boolean truncated = body.length() > MAX_RESPONSE_BODY_LOG_CHARS;
        String display = truncated ? body.substring(0, MAX_RESPONSE_BODY_LOG_CHARS) : body;
        String suffix = truncated ? "... [truncated, " + (body.length() - MAX_RESPONSE_BODY_LOG_CHARS) + " chars omitted]" : "";
        String msg = String.format("Abios upstream response (%d bytes): %s%s", payload.length, display, suffix);
        if (abios.isLogUpstreamPayload()) log.info(msg);
        else log.debug(msg);
    }

    private void logParsedSeriesShape(AbiosEnrichedDocument doc) {
        if (!abios.isLogUpstreamPayload() && !log.isDebugEnabled()) {
            return;
        }
        if (doc == null) {
            log.info("Abios parsed series shape: document=null");
            return;
        }
        int seriesN = 0, slots = 0, withTeamId = 0, slotsWithPlayers = 0, playerNodes = 0;
        for (AbiosSeriesNode s : doc.allSeries()) {
            seriesN++;
            for (AbiosTeamNode t : s.allTeams()) {
                slots++;
                if (t.effectiveTeamId() != null) withTeamId++;
                int pc = t.allPlayers().size();
                if (pc > 0) {
                    slotsWithPlayers++;
                    playerNodes += pc;
                }
            }
        }
        log.info("Abios parsed series shape: series={} participantSlots={} withTeamId={} slotsWithPlayers={} inlinePlayers={}",
                seriesN, slots, withTeamId, slotsWithPlayers, playerNodes);
    }
}
