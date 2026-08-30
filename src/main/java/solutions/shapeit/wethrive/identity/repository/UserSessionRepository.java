package solutions.shapeit.wethrive.identity.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import solutions.shapeit.wethrive.identity.entity.UserSession;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {
    Optional<UserSession> findByAccessTokenHashAndRevokedAtIsNullAndAccessExpiresAtAfter(String hash, Instant now);
    Optional<UserSession> findByRefreshTokenHash(String hash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from UserSession session where session.refreshTokenHash = :hash")
    Optional<UserSession> findByRefreshTokenHashForUpdate(String hash);
    Optional<UserSession> findByPreviousRefreshTokenHash(String hash);
    List<UserSession> findAllByUserIdAndRevokedAtIsNullOrderByLastUsedAtDesc(UUID userId);
    List<UserSession> findAllByTokenFamilyIdAndRevokedAtIsNull(UUID tokenFamilyId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from UserSession session where session.tokenFamilyId = :tokenFamilyId "
            + "and session.revokedAt is null order by session.id")
    List<UserSession> findAllActiveByTokenFamilyIdForUpdate(UUID tokenFamilyId);
    List<UserSession> findAllByDeviceIdAndRevokedAtIsNull(UUID deviceId);
}
