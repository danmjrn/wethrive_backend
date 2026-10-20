package solutions.shapeit.wethrive.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import solutions.shapeit.wethrive.common.model.BaseEntity;

@Entity
@Table(name = "application_invitations")
@Getter
@Setter
public class ApplicationInvitation extends BaseEntity {
    @Column(nullable = false, length = 320) private String email;
    @Column(nullable = false, length = 320) private String normalizedEmail;
    @Column(nullable = false, unique = true, length = 64) private String tokenHash;
    @Column(nullable = false) private UUID invitedByUserId;
    @Column(nullable = false) private Instant expiresAt;
    private Instant acceptedAt;
    private Instant revokedAt;
}
