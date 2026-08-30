package solutions.shapeit.wethrive.device.entity;

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
@Table(name = "devices")
@Getter
@Setter
@NoArgsConstructor
public class Device extends BaseEntity {
    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 120)
    private String displayName;

    @Column(length = 80)
    private String platform;

    @Column(length = 80)
    private String browser;

    private Instant lastSeenAt;

    @Column(nullable = false)
    private boolean offlineAccessEnabled;

    private Instant offlineGrantExpiresAt;
    private Instant revokedAt;
}
