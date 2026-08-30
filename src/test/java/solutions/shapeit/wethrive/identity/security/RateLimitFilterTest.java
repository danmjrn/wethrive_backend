package solutions.shapeit.wethrive.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import solutions.shapeit.wethrive.TestProperties;

class RateLimitFilterTest {
    private final RateLimitFilter filter = new RateLimitFilter(Clock.systemUTC(), TestProperties.create());

    @Test
    void usesRightmostUntrustedHopBehindDockerGatewayAndIgnoresSpoofedPrefix() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.17.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.9, 203.0.113.7");
        assertThat(filter.clientAddress(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void neverTrustsForwardedHeaderFromDirectUntrustedPeer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.8");
        request.addHeader("X-Forwarded-For", "198.51.100.9");
        assertThat(filter.clientAddress(request)).isEqualTo("203.0.113.8");
    }

    @Test
    void limitsSensitiveAuthenticationRequestsAndMarksTheResponseNonCacheable() throws Exception {
        RateLimitFilter fixed = new RateLimitFilter(
                Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC), TestProperties.create(2, 300));
        MockHttpServletResponse response = null;

        for (int requestNumber = 1; requestNumber <= 3; requestNumber++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            request.setRemoteAddr("198.51.100.8");
            response = new MockHttpServletResponse();
            fixed.doFilterInternal(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(requestNumber <= 2 ? 200 : 429);
        }

        assertThat(response).isNotNull();
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).doesNotContain("198.51.100.8");
    }
}
