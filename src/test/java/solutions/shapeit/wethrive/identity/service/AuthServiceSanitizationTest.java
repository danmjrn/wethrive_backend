package solutions.shapeit.wethrive.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthServiceSanitizationTest {
    @Test
    void sanitizesHostileLongUserAgentUsingSanitizedLength() {
        String hostile = "X\r\n".repeat(300);
        String sanitized = AuthService.sanitizeUserAgent(hostile);
        assertThat(sanitized).hasSize(255).doesNotContain("\r", "\n");
    }
}
