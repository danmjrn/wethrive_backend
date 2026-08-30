package solutions.shapeit.wethrive.common.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
                                String vapidSubject, String mailFrom) {}

    public record Reminders(boolean schedulerEnabled, Duration schedulerDelay, int batchSize) {}
}
