package solutions.shapeit.wethrive.space.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import solutions.shapeit.wethrive.space.entity.Space;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SpaceType;

public interface SpaceRepository extends JpaRepository<Space, UUID> {
    Optional<Space> findByIdAndDeletedAtIsNull(UUID id);
    boolean existsBySlug(String slug);
    boolean existsByOwnerUserIdAndTypeAndDeletedAtIsNull(UUID ownerUserId, SpaceType type);
}
