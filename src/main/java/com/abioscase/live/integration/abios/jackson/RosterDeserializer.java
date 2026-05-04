package com.abioscase.live.integration.abios.jackson;


import com.abioscase.live.integration.abios.model.AbiosPlayerNode;
import com.abioscase.live.integration.abios.model.AbiosTeamNode;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonDeserializer;

public class RosterDeserializer extends JsonDeserializer<AbiosTeamNode.Roster> {
    @Override
    public AbiosTeamNode.Roster deserialize(JsonParser p, com.fasterxml.jackson.databind.DeserializationContext ctxt)
            throws java.io.IOException {
        JsonToken t = p.currentToken();
        if (t == JsonToken.START_ARRAY) {
            java.util.List<AbiosPlayerNode> list = ctxt.readValue(p,
                    ctxt.getTypeFactory().constructCollectionType(java.util.List.class, AbiosPlayerNode.class));
            AbiosTeamNode.Roster r = new AbiosTeamNode.Roster();
            r.setPlayers(list);
            return r;
        } else if (t == JsonToken.START_OBJECT) {
            return ctxt.readValue(p, AbiosTeamNode.Roster.class);
        } else if (t != JsonToken.VALUE_NULL) {
            p.skipChildren();
        }
        return null;
    }
}
