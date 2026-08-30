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
@Table(name = "used_refresh_tokens")
@Getter @Setter @NoArgsConstructor
public class UsedRefreshToken extends BaseEntity {
    @Column(nullable = false, unique = true, length = 64) private String tokenHash;
    @Column(nullable = false) private UUID sessionId;
    @Column(nullable = false) private UUID tokenFamilyId;
    @Column(nullable = false) private UUID userId;
    @Column(nullable = false) private Instant expiresAt;
}
