package solutions.shapeit.wethrive;

import java.time.Duration;
import java.util.List;
import solutions.shapeit.wethrive.common.config.ApplicationProperties;

public final class TestProperties {
    private TestProperties() {}

    public static ApplicationProperties create() {
        return create(10, 300);
    }

    public static ApplicationProperties create(int authRateLimitPerMinute, int apiRateLimitPerMinute) {
        return new ApplicationProperties(
                new ApplicationProperties.Branding("WeThrive", "WeThrive", "Test", "localhost",
                        "http://localhost:3000", "ZAR", "en-ZA", "Africa/Johannesburg",
                        "Shape It Solutions", "https://shapeit.solutions"),
                new ApplicationProperties.Security("OPEN", false, "Lax", Duration.ofMinutes(15),
                        Duration.ofDays(30), "bootstrap-test-token", true, "", "", "offline-test-v1",
                        "development-push-secret-key-32bytes", List.of("127.0.0.1/32", "::1/128", "172.16.0.0/12"),
                        authRateLimitPerMinute, apiRateLimitPerMinute),
                new ApplicationProperties.Offline(30),
                new ApplicationProperties.Notifications(false, "", "", "mailto:test@example.com", "test@example.com"),
                new ApplicationProperties.Reminders(false, Duration.ofMinutes(1), 100));
    }
}
