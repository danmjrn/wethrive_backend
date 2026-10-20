package solutions.shapeit.wethrive.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ApplicationPropertiesBindingTest {
    @Test
    void bindsNotificationsDespiteTheSourceCompatibleOverloadedConstructor() {
        var source = new MapConfigurationPropertySource(Map.of(
                "wethrive.notifications.mail-provider", "GRAPH",
                "wethrive.notifications.mail-from", "WeThrive <mail@example.com>",
                "wethrive.notifications.graph.tenant-id", "11111111-2222-3333-4444-555555555555",
                "wethrive.notifications.graph.client-id", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                "wethrive.notifications.graph.client-secret", "synthetic-test-credential",
                "wethrive.notifications.graph.sender", "mail@example.com",
                "wethrive.notifications.graph.connect-timeout", "PT5S",
                "wethrive.notifications.graph.request-timeout", "PT15S"));
        var properties = new Binder(source).bind("wethrive", Bindable.of(ApplicationProperties.class)).get();
        assertThat(properties.notifications()).isNotNull();
        assertThat(properties.notifications().mailProvider()).isEqualTo("GRAPH");
        assertThat(properties.notifications().graph().sender()).isEqualTo("mail@example.com");
        assertThat(properties.notifications().graph().requestTimeout()).isEqualTo(Duration.ofSeconds(15));
    }
}
