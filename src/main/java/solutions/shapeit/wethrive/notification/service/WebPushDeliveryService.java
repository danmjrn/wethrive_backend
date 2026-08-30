package solutions.shapeit.wethrive.notification.service;

import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import solutions.shapeit.wethrive.common.config.ApplicationProperties;
import solutions.shapeit.wethrive.device.repository.DeviceRepository;
import solutions.shapeit.wethrive.notification.entity.PushDeliveryAttempt;
import solutions.shapeit.wethrive.notification.entity.PushSubscription;
import solutions.shapeit.wethrive.notification.repository.PushDeliveryAttemptRepository;
import solutions.shapeit.wethrive.notification.repository.PushSubscriptionRepository;
import solutions.shapeit.wethrive.reminder.entity.ReminderOccurrence;
import solutions.shapeit.wethrive.reminder.repository.ReminderOccurrenceRepository;
import tools.jackson.databind.ObjectMapper;

@Service
public class WebPushDeliveryService {
    private static final int MAX_ATTEMPTS = 3;
    private final PushSubscriptionRepository subscriptions;
    private final PushDeliveryAttemptRepository attempts;
    private final DeviceRepository devices;
    private final ReminderOccurrenceRepository occurrences;
    private final WebPushGateway gateway;
    private final ApplicationProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;

    public WebPushDeliveryService(PushSubscriptionRepository subscriptions, PushDeliveryAttemptRepository attempts,
                                  DeviceRepository devices,
                                  ReminderOccurrenceRepository occurrences, WebPushGateway gateway,
                                  ApplicationProperties properties, ObjectMapper mapper, Clock clock) {
        this.subscriptions = subscriptions; this.attempts = attempts; this.devices = devices; this.occurrences = occurrences;
        this.gateway = gateway; this.properties = properties; this.mapper = mapper; this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReminder(ReminderPushRequested request) {
        occurrences.findById(request.occurrenceId()).ifPresent(this::deliver);
    }

    @Transactional
    public int sendTest(UUID userId) {
        int delivered = 0;
        for (PushSubscription subscription : active(userId)) {
            String key = "test:" + UUID.randomUUID() + ":" + subscription.getId();
            PushDeliveryAttempt attempt = newAttempt(null, userId, subscription.getId(), 1, key, Instant.now(clock));
            attempts.save(attempt);
            if (send(attempt, subscription, genericPayload("WeThrive", "Push notifications are ready", null, "GENERIC"))) delivered++;
        }
        return delivered;
    }

    @Transactional
    public void deliver(ReminderOccurrence occurrence) {
        for (PushSubscription subscription : active(occurrence.getUserId())) {
            String key = occurrence.getId() + ":" + subscription.getId() + ":1";
            if (attempts.existsByIdempotencyKey(key)) continue;
            PushDeliveryAttempt attempt = newAttempt(occurrence.getId(), occurrence.getUserId(), subscription.getId(), 1, key, Instant.now(clock));
            attempts.save(attempt);
            send(attempt, subscription, payload(occurrence, subscription));
        }
    }

    @Scheduled(fixedDelayString = "PT1M")
    @Transactional
    public void retry() {
        if (!properties.notifications().webPushEnabled()) return;
        for (PushDeliveryAttempt attempt : attempts.lockRetries(Instant.now(clock), 100)) {
            PushSubscription subscription = subscriptions.findById(attempt.getSubscriptionId()).orElse(null);
            if (subscription == null || !isActive(subscription, Instant.now(clock))) {
                attempt.setStatus("ABANDONED"); attempts.save(attempt); continue;
            }
            ReminderOccurrence occurrence = attempt.getReminderOccurrenceId() == null ? null : occurrences.findById(attempt.getReminderOccurrenceId()).orElse(null);
            String payload = occurrence == null ? genericPayload("WeThrive", "Push notifications are ready", null, "GENERIC") : payload(occurrence, subscription);
            send(attempt, subscription, payload);
        }
    }

    private boolean send(PushDeliveryAttempt attempt, PushSubscription subscription, String payload) {
        WebPushGateway.DeliveryResult result = gateway.send(subscription, payload);
        Instant now = Instant.now(clock); attempt.setAttemptedAt(now); attempt.setResponseStatus(result.statusCode());
        if (result.success()) {
            attempt.setStatus("DELIVERED"); subscription.setLastSuccessAt(now); subscription.setFailureCount(0);
            if (attempt.getReminderOccurrenceId() != null) occurrences.findById(attempt.getReminderOccurrenceId()).ifPresent(occurrence -> {
                if (occurrence.getDeliveredAt() == null) {
                    occurrence.setStatus(solutions.shapeit.wethrive.common.domain.DomainEnums.ReminderStatus.DELIVERED);
                    occurrence.setDeliveredAt(now);
                    occurrences.save(occurrence);
                }
            });
            subscriptions.save(subscription); attempts.save(attempt); return true;
        }
        subscription.setLastFailureAt(now); subscription.setFailureCount(subscription.getFailureCount() + 1);
        attempt.setFailureReason(result.permanentFailure() ? "permanent_push_rejection" : "transient_delivery_failure");
        if (result.permanentFailure()) {
            attempt.setStatus("FAILED_PERMANENT"); subscription.setEnabled(false); subscription.setRevokedAt(now);
        } else if (attempt.getAttemptNumber() < MAX_ATTEMPTS) {
            attempt.setStatus("FAILED_RETRY_SCHEDULED");
            int nextNumber = attempt.getAttemptNumber() + 1;
            String key = (attempt.getReminderOccurrenceId() == null ? "test:" + attempt.getId() : attempt.getReminderOccurrenceId().toString())
                    + ":" + subscription.getId() + ":" + nextNumber;
            if (!attempts.existsByIdempotencyKey(key)) attempts.save(newAttempt(attempt.getReminderOccurrenceId(), attempt.getUserId(),
                    subscription.getId(), nextNumber, key, now.plusSeconds((long) Math.pow(5, nextNumber) * 60)));
        } else attempt.setStatus("FAILED_EXHAUSTED");
        subscriptions.save(subscription); attempts.save(attempt); return false;
    }

    private String payload(ReminderOccurrence occurrence, PushSubscription subscription) {
        boolean detailed = subscription.isDetailedContentEnabled();
        String body = detailed && !subscription.isHideSpaceNames() ? occurrence.getBody() : "You have a reminder waiting.";
        String title = detailed ? occurrence.getTitle() : "WeThrive";
        return genericPayload(title, body, occurrence.getId(), detailed ? "DETAILED" : "GENERIC");
    }

    private String genericPayload(String title, String body, UUID occurrenceId, String privacy) {
        String key = occurrenceId == null ? "wethrive-test" : "reminder-" + occurrenceId;
        Map<String, Object> value = new LinkedHashMap<>(); value.put("title", title); value.put("body", body);
        value.put("url", occurrenceId == null ? "/reminders" : "/reminders?occurrenceId=" + occurrenceId);
        value.put("tag", key);
        value.put("privacy", privacy);
        value.put("idempotencyKey", key);
        value.put("actions", List.of(Map.of("action", "open", "title", "Open reminders")));
        if (occurrenceId != null) value.put("occurrenceId", occurrenceId.toString());
        try { return mapper.writeValueAsString(value); } catch (Exception ex) { return "{\"title\":\"WeThrive\",\"body\":\"You have a reminder waiting.\"}"; }
    }

    private List<PushSubscription> active(UUID userId) {
        Instant now = Instant.now(clock);
        return subscriptions.findAllByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(userId).stream()
                .filter(subscription -> isActive(subscription, now)).toList();
    }
    private boolean isActive(PushSubscription subscription, Instant now) {
        return subscription.isEnabled()
                && subscription.getRevokedAt() == null
                && (subscription.getExpirationTime() == null || subscription.getExpirationTime().isAfter(now))
                && devices.findByIdAndUserIdAndRevokedAtIsNull(subscription.getDeviceId(), subscription.getUserId()).isPresent();
    }
    private PushDeliveryAttempt newAttempt(UUID occurrenceId, UUID userId, UUID subscriptionId, int number, String key, Instant next) {
        PushDeliveryAttempt attempt = new PushDeliveryAttempt(); attempt.setReminderOccurrenceId(occurrenceId); attempt.setUserId(userId);
        attempt.setSubscriptionId(subscriptionId); attempt.setAttemptNumber(number); attempt.setIdempotencyKey(key);
        attempt.setStatus(number == 1 ? "PENDING" : "RETRY_PENDING"); attempt.setNextAttemptAt(next); return attempt;
    }
}
