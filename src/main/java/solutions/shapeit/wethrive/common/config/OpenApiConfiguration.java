package solutions.shapeit.wethrive.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI weThriveOpenApi(ApplicationProperties properties) {
        return new OpenAPI()
                .info(new Info().title(properties.branding().name() + " API")
                        .description(properties.branding().tagline()).version("1.0.0"))
                .components(new Components()
                        .addSecuritySchemes("cookieAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE)
                                .name("WT_ACCESS")));
    }
}
