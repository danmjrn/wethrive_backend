package solutions.shapeit.wethrive.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import solutions.shapeit.wethrive.TestProperties;

class SecurityConfigurationTest {
    private final SecurityConfiguration security = new SecurityConfiguration();

    @Test
    void corsAllowsOnlyTheConfiguredApplicationOriginWithCredentials() {
        var source = security.corsConfigurationSource(TestProperties.create());
        var request = new MockHttpServletRequest("OPTIONS", "/api/v1/auth/login");
        CorsConfiguration configuration = source.getCorsConfiguration(request);

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactly("http://localhost:3000");
        assertThat(configuration.getAllowedOriginPatterns()).isNullOrEmpty();
        assertThat(configuration.getAllowCredentials()).isTrue();
        assertThat(configuration.checkOrigin("http://localhost:3000")).isEqualTo("http://localhost:3000");
        assertThat(configuration.checkOrigin("https://attacker.example")).isNull();
        assertThat(configuration.getAllowedHeaders()).contains("X-XSRF-TOKEN");
    }
}
