package solutions.shapeit.wethrive.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiRequestSizeFilterTest {
    private final ApiRequestSizeFilter filter = new ApiRequestSizeFilter(8);

    @Test
    void rejectsADeclaredApiBodyBeforeTheControllerReadsIt() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/v1/sync/push", "123456789");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("payload_too_large").doesNotContain("123456789");
    }

    @Test
    void countsTheStreamSoARequestCannotBypassTheLimitWithUnknownLength() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sync/push") {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
        };
        request.setContentType("application/json");
        request.setContent("123456789".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain consumingChain = (wrapped, ignored) -> wrapped.getInputStream().readAllBytes();

        filter.doFilterInternal(request, response, consumingChain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("payload_too_large");
    }

    @Test
    void doesNotApplyTheJsonBodyLimitOutsideTheApi() throws Exception {
        MockHttpServletRequest request = request("POST", "/webhook-from-another-application", "123456789");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest request(String method, String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
