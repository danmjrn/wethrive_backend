package solutions.shapeit.wethrive.identity.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import solutions.shapeit.wethrive.identity.entity.UsedRefreshToken;

public interface UsedRefreshTokenRepository extends JpaRepository<UsedRefreshToken, UUID> {
    Optional<UsedRefreshToken> findByTokenHashAndExpiresAtAfter(String hash, Instant now);
    long deleteByExpiresAtBefore(Instant now);
}
