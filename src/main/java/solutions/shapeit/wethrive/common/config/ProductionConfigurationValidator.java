package solutions.shapeit.wethrive.common.config;

import java.net.URI;
import java.net.InetAddress;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Locale;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class ProductionConfigurationValidator {
    public ProductionConfigurationValidator(ApplicationProperties properties, Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) return;

        validatePublicOrigin(properties.branding().host(), properties.branding().baseUrl());
        validateRegionalDefaults(properties.branding());
        if (!properties.security().secureCookies()) {
            throw new IllegalStateException("COOKIE_SECURE must be true in production");
        }
        String sameSite = properties.security().sameSite();
        if (!"Lax".equalsIgnoreCase(sameSite) && !"Strict".equalsIgnoreCase(sameSite)) {
            throw new IllegalStateException("COOKIE_SAME_SITE must be Lax or Strict in production");
        }
        String registrationMode = normalized(properties.security().registrationMode());
        if (!"INVITE_ONLY".equals(registrationMode) && !"CLOSED".equals(registrationMode)) {
            throw new IllegalStateException("REGISTRATION_MODE must be INVITE_ONLY or CLOSED in production");
        }
        requireDuration("ACCESS_SESSION_TTL", properties.security().accessSessionTtl(), Duration.ofMinutes(1), Duration.ofHours(1));
        requireDuration("REFRESH_SESSION_TTL", properties.security().refreshSessionTtl(), Duration.ofDays(1), Duration.ofDays(90));
        if (properties.offline().grantDays() < 1 || properties.offline().grantDays() > 90) {
            throw new IllegalStateException("OFFLINE_GRANT_DAYS must be between 1 and 90 in production");
        }
        validateTrustedProxies(properties.security().trustedProxyCidrs());
        requireRange("AUTH_RATE_LIMIT_PER_MINUTE", properties.security().authRateLimitPerMinute(), 5, 60);
        requireRange("API_RATE_LIMIT_PER_MINUTE", properties.security().apiRateLimitPerMinute(), 60, 1_000);

        requireKey("OFFLINE_SIGNING_PRIVATE_KEY", properties.security().offlineSigningPrivateKey());
        requireKey("OFFLINE_VERIFICATION_PUBLIC_KEY", properties.security().offlineVerificationPublicKey());
        if (blank(properties.security().offlineKeyId())) throw new IllegalStateException("OFFLINE_KEY_ID must be set in production");
        requireSecret("PUSH_ENCRYPTION_KEY", properties.security().subscriptionEncryptionKey());
        if (properties.security().exposeAccountTokens()) {
            throw new IllegalStateException("EXPOSE_ACCOUNT_TOKENS must be false in production");
        }
        String bootstrap = properties.security().bootstrapToken();
        if (bootstrap != null && !bootstrap.isBlank()) {
            requireSecret("APP_BOOTSTRAP_TOKEN", bootstrap);
            if (!booleanProperty(environment, "ALLOW_BOOTSTRAP_TOKEN", false)) {
                throw new IllegalStateException("ALLOW_BOOTSTRAP_TOKEN must be true while APP_BOOTSTRAP_TOKEN is configured");
            }
        } else if (booleanProperty(environment, "ALLOW_BOOTSTRAP_TOKEN", false)) {
            throw new IllegalStateException("ALLOW_BOOTSTRAP_TOKEN must be false when APP_BOOTSTRAP_TOKEN is empty");
        }
        if (properties.notifications().webPushEnabled()) {
            List<String> missing = new java.util.ArrayList<>();
            if (blank(properties.notifications().vapidPublicKey())) missing.add("VAPID_PUBLIC_KEY");
            if (blank(properties.notifications().vapidPrivateKey())) missing.add("VAPID_PRIVATE_KEY");
            if (blank(properties.notifications().vapidSubject())) missing.add("VAPID_SUBJECT");
            if (!missing.isEmpty()) throw new IllegalStateException("Web Push is enabled but values are missing: " + String.join(", ", missing));
        }

        String datasourceUrl = first(environment.getProperty("spring.datasource.url"), environment.getProperty("DATABASE_URL"));
        if (blank(datasourceUrl) || !datasourceUrl.startsWith("jdbc:postgresql://") || containsDevelopmentHost(datasourceUrl)) {
            throw new IllegalStateException("DATABASE_URL must identify a non-development PostgreSQL service in production");
        }
        String databaseUser = first(environment.getProperty("spring.datasource.username"), environment.getProperty("POSTGRES_USER"));
        String databasePassword = first(environment.getProperty("spring.datasource.password"), environment.getProperty("POSTGRES_PASSWORD"));
        if (blank(databaseUser)) throw new IllegalStateException("POSTGRES_USER must be set in production");
        requireCredential("POSTGRES_PASSWORD", databasePassword, 16);
        if (databasePassword.equals(databaseUser)) {
            throw new IllegalStateException("POSTGRES_PASSWORD must not equal POSTGRES_USER");
        }

        String forwardHeaders = first(environment.getProperty("server.forward-headers-strategy"),
                environment.getProperty("SERVER_FORWARD_HEADERS_STRATEGY"));
        if (!"framework".equalsIgnoreCase(forwardHeaders)) {
            throw new IllegalStateException("SERVER_FORWARD_HEADERS_STRATEGY must be framework in production");
        }

        long maxApiRequestBytes = longProperty(environment, "MAX_API_REQUEST_BYTES", 8_388_608L);
        if (maxApiRequestBytes < 65_536L || maxApiRequestBytes > 16_777_216L) {
            throw new IllegalStateException("MAX_API_REQUEST_BYTES must be between 65536 and 16777216 in production");
        }

        String mailHost = first(environment.getProperty("spring.mail.host"), environment.getProperty("MAIL_HOST"));
        if (blank(mailHost) || containsDevelopmentHost(mailHost) || "mailpit".equalsIgnoreCase(mailHost)) {
            throw new IllegalStateException("MAIL_HOST must identify a non-development SMTP service in production");
        }
        if (blank(properties.notifications().mailFrom())) {
            throw new IllegalStateException("MAIL_HOST and MAIL_FROM are required in production so account and invitation links can be delivered");
        }
        requireBoolean(environment, "MAIL_STARTTLS_ENABLE", true);
        requireBoolean(environment, "MAIL_STARTTLS_REQUIRED", true);
        requireBoolean(environment, "MAIL_TEST_CONNECTION", true);
        if (booleanProperty(environment, "MAIL_SMTP_AUTH", true)) {
            if (blank(environment.getProperty("MAIL_USERNAME")) || blank(environment.getProperty("MAIL_PASSWORD"))) {
                throw new IllegalStateException("MAIL_USERNAME and MAIL_PASSWORD are required when MAIL_SMTP_AUTH is true");
            }
            requireCredential("MAIL_PASSWORD", environment.getProperty("MAIL_PASSWORD"), 16);
        }
    }

    private void requireSecret(String name, String value) {
        requireCredential(name, value, 32);
    }

    private void requireCredential(String name, String value, int minimumLength) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (value == null || value.length() < minimumLength || lower.contains("development") || lower.contains("change_me")
                || lower.contains("change-me") || lower.contains("changeme") || lower.contains("default")
                || lower.contains("password") || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException(name + " must be an explicit production secret of at least " + minimumLength + " characters");
        }
    }
    private void requireKey(String name, String value) {
        if (blank(value) || value.length() < 80) {
            throw new IllegalStateException(name + " must be an explicit base64-encoded DER key in production");
        }
    }

    private void validatePublicOrigin(String configuredHost, String configuredBaseUrl) {
        if (blank(configuredHost) || blank(configuredBaseUrl)) {
            throw new IllegalStateException("APP_HOST and APP_BASE_URL are required in production");
        }
        try {
            URI origin = URI.create(configuredBaseUrl);
            String host = origin.getHost();
            boolean originOnly = "https".equalsIgnoreCase(origin.getScheme())
                    && host != null
                    && origin.getUserInfo() == null
                    && (origin.getPath() == null || origin.getPath().isEmpty())
                    && origin.getQuery() == null
                    && origin.getFragment() == null;
            if (!originOnly || !host.equalsIgnoreCase(configuredHost) || isDevelopmentHost(host)) {
                throw new IllegalStateException("APP_BASE_URL must be an HTTPS production origin whose host exactly matches APP_HOST");
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("APP_BASE_URL must be a valid HTTPS production origin", ex);
        }
    }

    private void requireDuration(String name, Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalStateException(name + " must be between " + minimum + " and " + maximum + " in production");
        }
    }

    private void requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalStateException(name + " must be between " + minimum + " and " + maximum + " in production");
        }
    }

    private void validateRegionalDefaults(ApplicationProperties.Branding branding) {
        try {
            Currency.getInstance(branding.defaultCurrency());
            ZoneId.of(branding.defaultTimeZone());
            Locale locale = new Locale.Builder().setLanguageTag(branding.defaultLocale()).build();
            if (locale.getLanguage().isBlank() || "und".equals(locale.getLanguage())) throw new IllegalArgumentException();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("DEFAULT_CURRENCY, DEFAULT_LOCALE, and DEFAULT_TIME_ZONE must be valid production identifiers", ex);
        }
    }

    private void validateTrustedProxies(List<String> cidrs) {
        if (cidrs == null || cidrs.isEmpty() || !cidrs.contains("127.0.0.1/32") || !cidrs.contains("::1/128")) {
            throw new IllegalStateException("TRUSTED_PROXY_CIDRS must include exact IPv4 and IPv6 loopback networks");
        }
        for (String cidr : cidrs) {
            try {
                String[] parts = cidr.trim().split("/", -1);
                if (parts.length != 2 || !parts[0].matches("[0-9A-Fa-f:.]+")) throw new IllegalArgumentException();
                byte[] address = InetAddress.getByName(parts[0]).getAddress();
                int prefix = Integer.parseInt(parts[1]);
                if (prefix <= 0 || prefix > address.length * 8) throw new IllegalArgumentException();
            } catch (RuntimeException | java.net.UnknownHostException ex) {
                throw new IllegalStateException("TRUSTED_PROXY_CIDRS contains an invalid or universal network", ex);
            }
        }
    }

    private void requireBoolean(Environment environment, String name, boolean required) {
        String value = environment.getProperty(name);
        if (value == null || (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))) {
            throw new IllegalStateException(name + " must be explicitly true or false in production");
        }
        if (Boolean.parseBoolean(value) != required) {
            throw new IllegalStateException(name + " must be " + required + " in production");
        }
    }

    private boolean booleanProperty(Environment environment, String name, boolean defaultValue) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) return defaultValue;
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalStateException(name + " must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    private long longProperty(Environment environment, String name, long defaultValue) {
        String value = environment.getProperty(name);
        if (blank(value)) return defaultValue;
        try { return Long.parseLong(value); }
        catch (NumberFormatException ex) { throw new IllegalStateException(name + " must be numeric", ex); }
    }

    private boolean containsDevelopmentHost(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.matches(".*(?:^|[/:.@-])(?:localhost|mailpit|127\\.0\\.0\\.1|0\\.0\\.0\\.0|\\[::1])(?:$|[/:.?-]).*")
                || lower.matches(".*\\.(?:test|invalid|local)(?::[0-9]+)?(?:/.*)?$");
    }

    private boolean isDevelopmentHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return !lower.contains(".") || lower.equals("localhost") || lower.endsWith(".localhost")
                || lower.endsWith(".local") || lower.endsWith(".test") || lower.endsWith(".invalid")
                || lower.equals("0.0.0.0") || lower.startsWith("127.") || lower.equals("::1");
    }

    private String first(String first, String second) { return blank(first) ? second : first; }
    private String normalized(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
