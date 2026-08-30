package solutions.shapeit.wethrive.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import solutions.shapeit.wethrive.common.model.BaseEntity;

@Entity
@Table(name = "push_delivery_attempts")
@Getter @Setter @NoArgsConstructor
public class PushDeliveryAttempt extends BaseEntity {
    private UUID reminderOccurrenceId;
    @Column(nullable = false) private UUID userId;
    @Column(nullable = false) private UUID subscriptionId;
    @Column(nullable = false) private int attemptNumber;
    @Column(nullable = false, unique = true, length = 180) private String idempotencyKey;
    @Column(nullable = false, length = 30) private String status;
    @Column(nullable = false) private Instant nextAttemptAt;
    private Instant attemptedAt;
    private Integer responseStatus;
    @Column(length = 100) private String failureReason;
}
