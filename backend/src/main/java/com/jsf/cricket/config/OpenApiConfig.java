package com.jsf.cricket.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Interactive API docs at /swagger-ui.html (OpenAPI JSON at /v3/api-docs). */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI cricketApi() {
        return new OpenAPI().info(new Info()
                .title("AI Cricket Analytics & Fantasy Team Advisor API")
                .version("1.0")
                .description("Text-to-SQL cricket chat, fantasy XI suggestions and ball-by-ball stats. "
                        + "CS3603-1 mini-project, team 24CSC21."));
    }
}
