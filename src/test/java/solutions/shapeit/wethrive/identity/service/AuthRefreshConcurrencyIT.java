package solutions.shapeit.wethrive.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.BootstrapRequest;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.LoginRequest;
import solutions.shapeit.wethrive.identity.repository.AppUserRepository;
import solutions.shapeit.wethrive.identity.security.SessionAuthenticationFilter;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "wethrive.security.bootstrap-token=integration-bootstrap-token",
        "wethrive.security.expose-account-tokens=true",
        "wethrive.security.secure-cookies=true",
        "wethrive.reminders.scheduler-enabled=false",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"
})
class AuthRefreshConcurrencyIT {
    private static final String OWNER_EMAIL = "owner@example.test";
    private static final String OWNER_PASSWORD = "StrongPassword123";
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine")
            .withDatabaseName("wethrive_auth").withUsername("wethrive").withPassword("test-password");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired AuthService auth;
    @Autowired WebApplicationContext context;
    @Autowired AppUserRepository users;
    MockMvc mvc;

    @BeforeEach
    void configureMockMvc() {
        if (!users.existsByNormalizedEmail(OWNER_EMAIL)) {
            auth.bootstrap(new BootstrapRequest(OWNER_EMAIL, OWNER_PASSWORD, "Owner",
                    "integration-bootstrap-token", UUID.randomUUID(), "Bootstrap browser"), "test-agent");
        }
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void csrfCookieUsesConfiguredSecureSameSitePolicy() throws Exception {
        mvc.perform(get("/api/v1/auth/csrf").secure(true))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var cookie = result.getResponse().getCookie("XSRF-TOKEN");
                    assertThat(cookie).isNotNull();
                    assertThat(cookie.getSecure()).isTrue();
                    assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
                });
    }

    @Test
    void authenticatedRequestsKeepTheCsrfCookieStableAndLogoutCanUseIt() throws Exception {
        var issued = auth.login(new LoginRequest(OWNER_EMAIL, OWNER_PASSWORD, UUID.randomUUID(),
                "CSRF browser", "integration", "mockmvc"), "test-agent");
        var csrfResult = mvc.perform(get("/api/v1/auth/csrf").secure(true))
                .andExpect(status().isOk())
                .andReturn();
        Cookie csrf = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
        Cookie access = new Cookie(SessionAuthenticationFilter.ACCESS_COOKIE, issued.accessToken());
        Cookie refresh = new Cookie(SessionAuthenticationFilter.REFRESH_COOKIE, issued.refreshToken());

        var sessionResult = mvc.perform(get("/api/v1/auth/session").secure(true).cookie(access, csrf))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(sessionResult.getResponse().getHeaders("Set-Cookie"))
                .noneMatch(value -> value.startsWith("XSRF-TOKEN="));

        mvc.perform(post("/api/v1/auth/logout").secure(true)
                        .cookie(access, refresh, csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void concurrentRefreshAllowsOneRotationThenRevokesTheWholeFamilyAsReuse() throws Exception {
        var issued = auth.login(new LoginRequest(OWNER_EMAIL, OWNER_PASSWORD, UUID.randomUUID(),
                "Refresh browser", "integration", "mockmvc"), "test-agent");
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        var first = executor.submit(() -> refreshAfter(start, issued.refreshToken()));
        var second = executor.submit(() -> refreshAfter(start, issued.refreshToken()));
        try {
            start.countDown();
            Outcome a = first.get(20, TimeUnit.SECONDS);
            Outcome b = second.get(20, TimeUnit.SECONDS);
            assertThat(java.util.List.of(a, b).stream().filter(outcome -> outcome.issue() != null).count()).isEqualTo(1);
            assertThat(java.util.List.of(a, b).stream().filter(outcome -> outcome.failure() != null).count()).isEqualTo(1);
            AuthService.SessionIssue successfulChild = a.issue() != null ? a.issue() : b.issue();
            assertThatThrownBy(() -> auth.refresh(successfulChild.refreshToken())).isInstanceOf(ApiException.class);
        } finally {
            first.cancel(true);
            second.cancel(true);
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Outcome refreshAfter(CountDownLatch start, String token) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Refresh start latch timed out");
            return new Outcome(auth.refresh(token), null);
        } catch (ApiException ex) {
            return new Outcome(null, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private record Outcome(AuthService.SessionIssue issue, ApiException failure) {}
}
