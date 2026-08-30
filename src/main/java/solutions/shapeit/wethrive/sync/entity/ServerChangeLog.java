package solutions.shapeit.wethrive.sync.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncOperationType;

@Entity
@Table(name = "server_change_log")
@Getter @Setter @NoArgsConstructor
public class ServerChangeLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long sequenceId;
    @Column(nullable = false) private UUID spaceId;
    @Column(nullable = false, length = 60) private String entityType;
    @Column(nullable = false) private UUID entityId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private SyncOperationType operationType;
    @Column(nullable = false) private long entityVersion;
    @Column(nullable = false) private UUID changedByUserId;
    @Column(nullable = false) private Instant changedAt;
    @Column(columnDefinition = "text") private String payload;
    @Column(nullable = false) private boolean tombstone;
}
