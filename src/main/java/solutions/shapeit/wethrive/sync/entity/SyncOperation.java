package solutions.shapeit.wethrive.sync.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncOperationType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncStatus;
import solutions.shapeit.wethrive.common.model.BaseEntity;

@Entity
@Table(name = "sync_operations")
@Getter @Setter @NoArgsConstructor
public class SyncOperation extends BaseEntity {
    @Column(nullable = false) private UUID userId;
    @Column(nullable = false) private UUID deviceId;
    @Column(nullable = false) private UUID spaceId;
    @Column(nullable = false, length = 60) private String moduleKey;
    @Column(nullable = false, length = 60) private String entityType;
    @Column(nullable = false) private UUID entityId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private SyncOperationType operationType;
    @Column(nullable = false, columnDefinition = "text") private String payload;
    private Long baseVersion;
    @Column(nullable = false) private Instant clientTimestamp;
    @Column(nullable = false) private int retryCount;
    @Column(length = 120) private String lastError;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private SyncStatus status;
    private Long resultVersion;
}
