package com.abioscase.live.integration.abios.jackson;

import com.abioscase.live.integration.abios.model.AbiosPlayerNode;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Atlas V3: roster/players may be inline {@code [...]} or an edge reference object {@code {"id": ...}} —
 * latter maps to empty inline list here (full roster fetch is a separate API hop).
 */
public final class MaybeArrayOfPlayersDeserializer extends JsonDeserializer<List<AbiosPlayerNode>> {

    @Override
    public List<AbiosPlayerNode> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken t = p.currentToken();
        if (t == JsonToken.VALUE_NULL) {
            return null;
        }
        if (t == JsonToken.START_ARRAY) {
            JavaType listType =
                    ctxt.getTypeFactory().constructCollectionType(ArrayList.class, AbiosPlayerNode.class);
            return ctxt.readValue(p, listType);
        }
        if (t == JsonToken.START_OBJECT) {
            p.skipChildren();
            return List.of();
        }
        throw JsonMappingException.from(
                p,
                String.format("roster/players: unexpected token %s (expected array, object edge, or null)", t));
    }
}
