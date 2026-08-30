package solutions.shapeit.wethrive.identity.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import solutions.shapeit.wethrive.identity.entity.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByNormalizedEmailAndDeletedAtIsNull(String normalizedEmail);
    boolean existsByNormalizedEmail(String normalizedEmail);
}
