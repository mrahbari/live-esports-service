package com.abioscase.live.integration.abios.model;

import com.abioscase.live.integration.abios.jackson.CoerceToStringDeserializer;
import com.abioscase.live.integration.abios.jackson.PlayerRoleDeserializer;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AbiosPlayerNode {

    @JsonDeserialize(using = CoerceToStringDeserializer.class)
    private String id;

    @JsonAlias({"nick_name"})
    private String nickname;

    private String ingameName;

    @JsonProperty("first_name")
    private String firstName;

    @JsonProperty("last_name")
    private String lastName;

    @JsonDeserialize(using = PlayerRoleDeserializer.class)
    private String role;

    @JsonIgnore
    public String effectiveNickname() {
        if (nickname != null && !nickname.isBlank()) return nickname;
        if (ingameName != null && !ingameName.isBlank()) return ingameName;
        return null;
    }
}
