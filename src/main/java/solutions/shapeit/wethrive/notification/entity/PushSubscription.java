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
@Table(name = "push_subscriptions")
@Getter @Setter @NoArgsConstructor
public class PushSubscription extends BaseEntity {
    @Column(nullable = false) private UUID userId;
    @Column(nullable = false) private UUID deviceId;
    @Column(nullable = false, length = 4096) private String endpointEncrypted;
    @Column(nullable = false, unique = true, length = 64) private String endpointHash;
    @Column(nullable = false, length = 2048) private String publicKeyEncrypted;
    @Column(nullable = false, length = 2048) private String authenticationSecretEncrypted;
    @Column(nullable = false) private boolean enabled = true;
    private Instant expirationTime;
    private Instant lastSuccessAt;
    private Instant lastFailureAt;
    @Column(nullable = false) private int failureCount;
    private Instant revokedAt;
    @Column(nullable = false) private boolean detailedContentEnabled;
    @Column(nullable = false) private boolean hideAmounts = true;
    @Column(nullable = false) private boolean hideSpaceNames = true;
    @Column(nullable = false) private boolean hideBudgetItemNames = true;
}
