package solutions.shapeit.wethrive.space.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import solutions.shapeit.wethrive.space.entity.Invitation;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {
    Optional<Invitation> findByTokenHash(String tokenHash);
    List<Invitation> findAllBySpaceIdOrderByCreatedAtDesc(UUID spaceId);
    Optional<Invitation> findByIdAndSpaceId(UUID id, UUID spaceId);
}
