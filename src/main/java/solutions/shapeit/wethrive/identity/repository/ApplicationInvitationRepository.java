package solutions.shapeit.wethrive.identity.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import solutions.shapeit.wethrive.identity.entity.ApplicationInvitation;

public interface ApplicationInvitationRepository extends JpaRepository<ApplicationInvitation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ApplicationInvitation> findByTokenHash(String tokenHash);
    List<ApplicationInvitation> findTop100ByOrderByCreatedAtDesc();
}
