package solutions.shapeit.wethrive.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationValidatorTest {
    private static final String KEY = "A".repeat(96);
    private static final String SYNTHETIC_DATABASE_CREDENTIAL = "D".repeat(32);

    @Test
    void acceptsAnExplicitHardenedProductionConfiguration() {
        assertThatCode(() -> new ProductionConfigurationValidator(properties(), environment()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDevelopmentOriginsAndMailServices() {
        var localOrigin = properties("localhost", "https://localhost", true, "Lax", "INVITE_ONLY");
        assertThatThrownBy(() -> new ProductionConfigurationValidator(localOrigin, environment()))
                .hasMessageContaining("APP_BASE_URL");

        var mailpit = environment().withProperty("MAIL_HOST", "mailpit")
                .withProperty("spring.mail.host", "mailpit");
        assertThatThrownBy(() -> new ProductionConfigurationValidator(properties(), mailpit))
                .hasMessageContaining("MAIL_HOST");
    }

    @Test
    void rejectsInsecureCookiesAndPublicRegistration() {
        assertThatThrownBy(() -> new ProductionConfigurationValidator(
                properties("finance.example.com", "https://finance.example.com", false, "Lax", "INVITE_ONLY"), environment()))
                .hasMessageContaining("COOKIE_SECURE");
        assertThatThrownBy(() -> new ProductionConfigurationValidator(
                properties("finance.example.com", "https://finance.example.com", true, "None", "INVITE_ONLY"), environment()))
                .hasMessageContaining("COOKIE_SAME_SITE");
        assertThatThrownBy(() -> new ProductionConfigurationValidator(
                properties("finance.example.com", "https://finance.example.com", true, "Lax", "OPEN"), environment()))
                .hasMessageContaining("REGISTRATION_MODE");
    }

    @Test
    void rejectsDevelopmentDatabaseDefaultsAndMissingProxyTrust() {
        var localDatabase = environment()
                .withProperty("DATABASE_URL", "jdbc:postgresql://localhost:5432/wethrive")
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/wethrive");
        assertThatThrownBy(() -> new ProductionConfigurationValidator(properties(), localDatabase))
                .hasMessageContaining("DATABASE_URL");

        var noForwarding = environment()
                .withProperty("SERVER_FORWARD_HEADERS_STRATEGY", "none")
                .withProperty("server.forward-headers-strategy", "none");
        assertThatThrownBy(() -> new ProductionConfigurationValidator(properties(), noForwarding))
                .hasMessageContaining("SERVER_FORWARD_HEADERS_STRATEGY");
    }

    @Test
    void rejectsUniversalTrustedProxyNetworks() {
        var base = properties();
        var security = base.security();
        var unsafe = new ApplicationProperties(base.branding(), new ApplicationProperties.Security(
                security.registrationMode(), security.secureCookies(), security.sameSite(),
                security.accessSessionTtl(), security.refreshSessionTtl(), security.bootstrapToken(),
                security.exposeAccountTokens(), security.offlineSigningPrivateKey(),
                security.offlineVerificationPublicKey(), security.offlineKeyId(),
                security.subscriptionEncryptionKey(), List.of("127.0.0.1/32", "::1/128", "0.0.0.0/0"),
                security.authRateLimitPerMinute(), security.apiRateLimitPerMinute()),
                base.offline(), base.notifications(), base.reminders());

        assertThatThrownBy(() -> new ProductionConfigurationValidator(unsafe, environment()))
                .hasMessageContaining("TRUSTED_PROXY_CIDRS");
    }

    @Test
    void rejectsTransientBootstrapAndOversizedRequestFlagsUnlessExplicit() {
        var withBootstrap = propertiesWithBootstrap("a-production-bootstrap-token-that-is-long-enough");
        assertThatThrownBy(() -> new ProductionConfigurationValidator(withBootstrap, environment()))
                .hasMessageContaining("ALLOW_BOOTSTRAP_TOKEN");

        var oversized = environment().withProperty("MAX_API_REQUEST_BYTES", "33554432");
        assertThatThrownBy(() -> new ProductionConfigurationValidator(properties(), oversized))
                .hasMessageContaining("MAX_API_REQUEST_BYTES");
    }

    private ApplicationProperties properties() {
        return properties("finance.example.com", "https://finance.example.com", true, "Lax", "INVITE_ONLY");
    }

    private ApplicationProperties properties(String host, String baseUrl, boolean secureCookies,
                                             String sameSite, String registrationMode) {
        return properties(host, baseUrl, secureCookies, sameSite, registrationMode, "");
    }

    private ApplicationProperties propertiesWithBootstrap(String bootstrapToken) {
        return properties("finance.example.com", "https://finance.example.com", true, "Lax", "INVITE_ONLY", bootstrapToken);
    }

    private ApplicationProperties properties(String host, String baseUrl, boolean secureCookies,
                                             String sameSite, String registrationMode, String bootstrapToken) {
        return new ApplicationProperties(
                new ApplicationProperties.Branding("WeThrive", "WeThrive", "Test", host, baseUrl,
                        "ZAR", "en-ZA", "Africa/Johannesburg", "Shape It Solutions",
                        "https://shapeit.solutions"),
                new ApplicationProperties.Security(registrationMode, secureCookies, sameSite,
                        Duration.ofMinutes(15), Duration.ofDays(30), bootstrapToken, false,
                        KEY, "B".repeat(96), "offline-v1", "C".repeat(40),
                        List.of("127.0.0.1/32", "::1/128", "172.16.0.0/12"), 10, 300),
                new ApplicationProperties.Offline(30),
                new ApplicationProperties.Notifications(false, "", "", "mailto:ops@example.com",
                        "WeThrive <no-reply@example.com>"),
                new ApplicationProperties.Reminders(false, Duration.ofMinutes(1), 100));
    }

    private MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("spring.profiles.active", "prod")
                .withProperty("DATABASE_URL", "jdbc:postgresql://postgres:5432/wethrive")
                .withProperty("spring.datasource.url", "jdbc:postgresql://postgres:5432/wethrive")
                .withProperty("POSTGRES_USER", "wethrive")
                .withProperty("spring.datasource.username", "wethrive")
                .withProperty("POSTGRES_PASSWORD", SYNTHETIC_DATABASE_CREDENTIAL)
                .withProperty("spring.datasource.password", SYNTHETIC_DATABASE_CREDENTIAL)
                .withProperty("SERVER_FORWARD_HEADERS_STRATEGY", "framework")
                .withProperty("server.forward-headers-strategy", "framework")
                .withProperty("ALLOW_BOOTSTRAP_TOKEN", "false")
                .withProperty("MAX_API_REQUEST_BYTES", "8388608")
                .withProperty("MAIL_HOST", "smtp.example.com")
                .withProperty("spring.mail.host", "smtp.example.com")
                .withProperty("MAIL_STARTTLS_ENABLE", "true")
                .withProperty("MAIL_STARTTLS_REQUIRED", "true")
                .withProperty("MAIL_TEST_CONNECTION", "true")
                .withProperty("MAIL_SMTP_AUTH", "true")
                .withProperty("MAIL_USERNAME", "smtp-user")
                .withProperty("MAIL_PASSWORD", "smtp-secret-value-2026");
    }
}
