package solutions.shapeit.wethrive.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class NotificationDtos {
    private NotificationDtos() {}
    public record SubscriptionRequest(@NotNull UUID id, @NotNull UUID deviceId,
                                      @NotBlank @Size(max = 4000) String endpoint,
                                      @NotBlank @Size(max = 1000) String publicKey,
                                      @NotBlank @Size(max = 1000) String authenticationSecret,
                                      Instant expirationTime, boolean detailedContentEnabled,
                                      boolean hideAmounts, boolean hideSpaceNames, boolean hideBudgetItemNames) {}
    public record SubscriptionResponse(UUID id, UUID deviceId, String endpointFingerprint, boolean enabled,
                                       Instant expirationTime, Instant lastSuccessAt, Instant lastFailureAt,
                                       int failureCount, boolean detailedContentEnabled, boolean hideAmounts,
                                       boolean hideSpaceNames, boolean hideBudgetItemNames, long version) {}
    public record TestNotificationResponse(boolean queued, String title, String body) {}
}
