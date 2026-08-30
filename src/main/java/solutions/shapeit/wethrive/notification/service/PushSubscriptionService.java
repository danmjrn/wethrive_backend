package solutions.shapeit.wethrive.notification.service;

import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import solutions.shapeit.wethrive.common.config.ApplicationProperties;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.device.repository.DeviceRepository;
import solutions.shapeit.wethrive.identity.service.SecureTokens;
import solutions.shapeit.wethrive.notification.dto.NotificationDtos.SubscriptionRequest;
import solutions.shapeit.wethrive.notification.dto.NotificationDtos.SubscriptionResponse;
import solutions.shapeit.wethrive.notification.dto.NotificationDtos.TestNotificationResponse;
import solutions.shapeit.wethrive.notification.entity.PushSubscription;
import solutions.shapeit.wethrive.notification.repository.PushSubscriptionRepository;

@Service
public class PushSubscriptionService {
    private final PushSubscriptionRepository subscriptions;
    private final DeviceRepository devices;
    private final SecretEncryptionService encryption;
    private final SecureTokens tokens;
    private final ApplicationProperties properties;
    private final Clock clock;
    private final WebPushDeliveryService delivery;

    public PushSubscriptionService(PushSubscriptionRepository subscriptions, DeviceRepository devices,
                                   SecretEncryptionService encryption, SecureTokens tokens,
                                   ApplicationProperties properties, Clock clock, WebPushDeliveryService delivery) {
        this.subscriptions = subscriptions; this.devices = devices; this.encryption = encryption;
        this.tokens = tokens; this.properties = properties; this.clock = clock;
        this.delivery = delivery;
    }

    @Transactional
    public SubscriptionResponse save(UUID userId, SubscriptionRequest request) {
        devices.findByIdAndUserIdAndRevokedAtIsNull(request.deviceId(), userId).orElseThrow(() -> ApiException.notFound("Device"));
        String hash = tokens.hash(request.endpoint());
        PushSubscription subscription = subscriptions.findByEndpointHash(hash).orElseGet(PushSubscription::new);
        if (subscription.getId() != null && !subscription.getUserId().equals(userId)) {
            throw ApiException.conflict("This browser subscription belongs to another local account; unsubscribe it before switching accounts");
        }
        if (subscription.getId() == null) {
            if (subscriptions.existsById(request.id())) throw ApiException.conflict("A subscription with this identifier already exists");
            subscription.setId(request.id()); subscription.setUserId(userId); subscription.setEndpointHash(hash);
        }
        subscription.setDeviceId(request.deviceId());
        subscription.setEndpointEncrypted(encryption.encrypt(request.endpoint()));
        subscription.setPublicKeyEncrypted(encryption.encrypt(request.publicKey()));
        subscription.setAuthenticationSecretEncrypted(encryption.encrypt(request.authenticationSecret()));
        subscription.setExpirationTime(request.expirationTime()); subscription.setEnabled(true); subscription.setRevokedAt(null);
        subscription.setDetailedContentEnabled(request.detailedContentEnabled()); subscription.setHideAmounts(request.hideAmounts());
        subscription.setHideSpaceNames(request.hideSpaceNames()); subscription.setHideBudgetItemNames(request.hideBudgetItemNames());
        return map(subscriptions.saveAndFlush(subscription));
    }

    @Transactional
    public List<SubscriptionResponse> list(UUID userId) { return subscriptions.findAllByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(userId).stream().map(this::map).toList(); }

    @Transactional
    public void revoke(UUID userId, UUID id) {
        PushSubscription subscription = require(userId, id); subscription.setEnabled(false); subscription.setRevokedAt(Instant.now(clock)); subscriptions.save(subscription);
    }

    @Transactional
    public TestNotificationResponse test(UUID userId) {
        if (!properties.notifications().webPushEnabled()) return new TestNotificationResponse(false, "Reminders are ready", "Push delivery is disabled; in-app reminders remain available.");
        if (subscriptions.findAllByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(userId).stream().noneMatch(PushSubscription::isEnabled)) {
            throw ApiException.badRequest("Enable a push subscription first");
        }
        int delivered = delivery.sendTest(userId);
        return new TestNotificationResponse(delivered > 0, "WeThrive", delivered > 0 ? "Test notification delivered." : "Push service did not accept the test notification.");
    }

    private PushSubscription require(UUID userId, UUID id) { return subscriptions.findByIdAndUserIdAndRevokedAtIsNull(id, userId).orElseThrow(() -> ApiException.notFound("Push subscription")); }
    private SubscriptionResponse map(PushSubscription s) { String hash = s.getEndpointHash(); return new SubscriptionResponse(s.getId(), s.getDeviceId(), hash.substring(0, 12), s.isEnabled(), s.getExpirationTime(), s.getLastSuccessAt(), s.getLastFailureAt(), s.getFailureCount(), s.isDetailedContentEnabled(), s.isHideAmounts(), s.isHideSpaceNames(), s.isHideBudgetItemNames(), s.getVersion()); }
}
