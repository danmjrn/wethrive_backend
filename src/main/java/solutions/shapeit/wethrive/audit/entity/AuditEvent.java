package solutions.shapeit.wethrive.audit.entity;

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
import solutions.shapeit.wethrive.common.domain.DomainEnums.AuditResult;

@Entity
@Table(name = "audit_events")
@Getter @Setter @NoArgsConstructor
public class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    private UUID actorUserId;
    private UUID spaceId;
    @Column(nullable = false, length = 100) private String action;
    @Column(length = 60) private String entityType;
    private UUID entityId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private AuditResult result;
    @Column(length = 500) private String metadata;
    @Column(nullable = false) private Instant createdAt;
}
