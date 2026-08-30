package solutions.shapeit.wethrive.identity.entity;

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
@Table(name = "user_sessions")
@Getter
@Setter
@NoArgsConstructor
public class UserSession extends BaseEntity {
    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private UUID deviceId;

    @Column(nullable = false, unique = true, length = 64)
    private String accessTokenHash;

    @Column(nullable = false, unique = true, length = 64)
    private String refreshTokenHash;

    @Column(length = 64)
    private String previousRefreshTokenHash;

    @Column(nullable = false)
    private UUID tokenFamilyId;

    @Column(nullable = false)
    private Instant issuedAt;

    @Column(nullable = false)
    private Instant accessExpiresAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant revokedAt;
    private Instant lastUsedAt;
    private Instant reuseDetectedAt;

    @Column(length = 255)
    private String userAgentMetadata;
}
