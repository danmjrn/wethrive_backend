package solutions.shapeit.wethrive.space.entity;

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
import solutions.shapeit.wethrive.common.domain.DomainEnums.Role;
import solutions.shapeit.wethrive.common.model.BaseEntity;

@Entity
@Table(name = "invitations")
@Getter
@Setter
@NoArgsConstructor
public class Invitation extends BaseEntity {
    @Column(nullable = false)
    private UUID spaceId;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(nullable = false, length = 320)
    private String normalizedEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private UUID invitedByUserId;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant acceptedAt;
    private Instant declinedAt;
    private Instant revokedAt;
}
