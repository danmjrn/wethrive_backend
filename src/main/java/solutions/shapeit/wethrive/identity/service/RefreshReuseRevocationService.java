package solutions.shapeit.wethrive.identity.service;

import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import solutions.shapeit.wethrive.identity.repository.UsedRefreshTokenRepository;
import solutions.shapeit.wethrive.identity.repository.UserSessionRepository;

/**
 * Persists refresh-token family revocation after the rotation-attempt transaction has
 * completed and released its session-row lock. The caller deliberately throws after
 * this method returns, so this independent transaction must commit first.
 */
@Service
public class RefreshReuseRevocationService {
    private final UserSessionRepository sessions;
    private final UsedRefreshTokenRepository usedRefreshTokens;
    private final Clock clock;

    public RefreshReuseRevocationService(UserSessionRepository sessions,
                                         UsedRefreshTokenRepository usedRefreshTokens,
                                         Clock clock) {
        this.sessions = sessions;
        this.usedRefreshTokens = usedRefreshTokens;
        this.clock = clock;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean revokeIfReused(String tokenHash) {
        Instant now = Instant.now(clock);
        Optional<UUID> family = usedRefreshTokens.findByTokenHashAndExpiresAtAfter(tokenHash, now)
                .map(used -> used.getTokenFamilyId());
        if (family.isEmpty()) {
            family = sessions.findByPreviousRefreshTokenHash(tokenHash).map(session -> session.getTokenFamilyId());
        }
        if (family.isEmpty()) return false;

        sessions.findAllActiveByTokenFamilyIdForUpdate(family.get()).forEach(session -> {
            session.setRevokedAt(now);
            session.setReuseDetectedAt(now);
            sessions.save(session);
        });
        return true;
    }
}
