package solutions.shapeit.wethrive.space.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import solutions.shapeit.wethrive.common.domain.DomainEnums.MembershipStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.Role;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SpaceType;

public final class SpaceDtos {
    private SpaceDtos() {}

    public record CreateSpaceRequest(@NotNull UUID id, @NotBlank @Size(max = 120) String name,
                                     @Size(min = 3, max = 3) String currencyCode,
                                     @Size(max = 35) String locale, @Size(max = 60) String timeZone) {}
    public record UpdateSpaceRequest(@NotBlank @Size(max = 120) String name, @NotNull long version) {}
    public record SpaceResponse(UUID id, SpaceType type, String name, String slug, UUID ownerUserId,
                                String currencyCode, String locale, String timeZone, Role currentRole,
                                long version, Instant createdAt, Instant updatedAt) {}
    public record MemberResponse(UUID membershipId, UUID spaceId, UUID userId, String displayName, Role role,
                                 MembershipStatus status, Instant joinedAt, long version) {}
    public record ChangeRoleRequest(@NotNull Role role, @NotNull long version) {}
    public record TransferOwnershipRequest(@NotNull UUID newOwnerMembershipId) {}
    public record InvitationRequest(UUID id, @Email @NotBlank @Size(max = 320) String email, @NotNull Role role) {}
    public record InvitationResponse(UUID id, UUID spaceId, String email, Role role, Instant expiresAt,
                                     Instant acceptedAt, Instant declinedAt, Instant revokedAt,
                                     String invitationToken, long version) {}
}
