package com.abioscase.live.integration.abios.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/** Maps JSON number or string into Java String (Atlas V3 numeric ids). */
public final class PlayerRoleDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);

        if (node.isTextual()) {
            return node.asText();
        }

        if (node.isObject()) {
            JsonNode id = node.get("id");
            return id != null ? id.asText() : null;
        }

        return null;
    }
}
