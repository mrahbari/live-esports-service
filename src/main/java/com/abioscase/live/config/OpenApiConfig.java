package com.abioscase.live.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Live Esports API")
                        .description("""
                                Real-time esports series, teams, and players aggregated from Abios Atlas.

                                ### Sparse fieldsets
                                All list endpoints support field selection via the `fields` query parameter.
                                Pass a comma-separated list of field names to receive only those fields per item.

                                **Examples**
                                - `GET /v1/players/live?fields=nickname,role`
                                - `GET /v1/teams/live?fields=name,abbreviation`
                                - `GET /v1/series/live?fields=seriesId,name,state`

                                Omitting `fields` (or leaving it empty) returns all fields.
                                Unknown field names are silently ignored.
                                The `meta` envelope is always returned in full regardless of `fields`.
                                """)
                        .version("1.0.0")
                        .contact(new Contact().name("Abios Case Study")));
    }
}
