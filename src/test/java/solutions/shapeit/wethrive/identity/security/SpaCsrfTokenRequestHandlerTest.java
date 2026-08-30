package solutions.shapeit.wethrive.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class SpaCsrfTokenRequestHandlerTest {
    @Test
    void resolvesTheRawCookieTokenFromTheSpaHeader() {
        var csrf = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "repository-token");
        var request = new MockHttpServletRequest();
        request.addHeader(csrf.getHeaderName(), csrf.getToken());

        assertThat(new SpaCsrfTokenRequestHandler().resolveCsrfTokenValue(request, csrf))
                .isEqualTo("repository-token");
    }
}
