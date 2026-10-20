package solutions.shapeit.wethrive.common.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "wethrive")
public record ApplicationProperties(
        Branding branding,
        Security security,
        Offline offline,
        Notifications notifications,
        Reminders reminders) {

    public record Branding(String name, String shortName, String tagline, String host, String baseUrl,
                           String defaultCurrency, String defaultLocale, String defaultTimeZone,
                           String parentCompany, String parentUrl) {}

    public record Security(String registrationMode, boolean secureCookies, String sameSite,
                           Duration accessSessionTtl, Duration refreshSessionTtl,
                           String bootstrapToken, boolean exposeAccountTokens,
                           String offlineSigningPrivateKey, String offlineVerificationPublicKey,
                           String offlineKeyId, String subscriptionEncryptionKey,
                           List<String> trustedProxyCidrs,
                           int authRateLimitPerMinute, int apiRateLimitPerMinute) {}

    public record Offline(int grantDays) {}

    public record Notifications(boolean webPushEnabled, String vapidPublicKey, String vapidPrivateKey,
                                String vapidSubject, String mailFrom, String mailProvider, Graph graph) {
        @ConstructorBinding
        public Notifications {
            mailProvider = mailProvider == null || mailProvider.isBlank() ? "SMTP" : mailProvider.trim().toUpperCase(java.util.Locale.ROOT);
            graph = graph == null ? new Graph(null, null, null, null, null, null) : graph;
        }

        /** Keeps existing development and test configuration source-compatible. */
        public Notifications(boolean webPushEnabled, String vapidPublicKey, String vapidPrivateKey,
                             String vapidSubject, String mailFrom) {
            this(webPushEnabled, vapidPublicKey, vapidPrivateKey, vapidSubject, mailFrom, "SMTP", null);
        }
    }

    public record Graph(String tenantId, String clientId, String clientSecret, String sender,
                        Duration connectTimeout, Duration requestTimeout) {
        public Graph {
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
            requestTimeout = requestTimeout == null ? Duration.ofSeconds(15) : requestTimeout;
        }

        // Configuration records can otherwise expose the credential through generated toString().
        @Override public String toString() { return "Graph[credentials=redacted]"; }
    }

    public record Reminders(boolean schedulerEnabled, Duration schedulerDelay, int batchSize) {}
}
