package solutions.shapeit.wethrive.space.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import solutions.shapeit.wethrive.common.domain.DomainEnums.MembershipStatus;
import solutions.shapeit.wethrive.space.entity.SpaceMembership;

public interface SpaceMembershipRepository extends JpaRepository<SpaceMembership, UUID> {
    Optional<SpaceMembership> findBySpaceIdAndUserIdAndStatusAndDeletedAtIsNull(UUID spaceId, UUID userId, MembershipStatus status);
    Optional<SpaceMembership> findBySpaceIdAndUserIdAndDeletedAtIsNull(UUID spaceId, UUID userId);
    List<SpaceMembership> findAllByUserIdAndStatusAndDeletedAtIsNull(UUID userId, MembershipStatus status);
    List<SpaceMembership> findAllBySpaceIdAndDeletedAtIsNullOrderByCreatedAt(UUID spaceId);
    List<SpaceMembership> findAllBySpaceIdInAndUserIdInAndDeletedAtIsNull(
            Collection<UUID> spaceIds, Collection<UUID> userIds);
    List<SpaceMembership> findAllBySpaceIdInAndUserIdAndStatus(Collection<UUID> spaceIds, UUID userId, MembershipStatus status);
}
