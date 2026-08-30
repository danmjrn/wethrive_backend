package solutions.shapeit.wethrive.identity.service;

import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import solutions.shapeit.wethrive.common.config.ApplicationProperties;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.device.entity.Device;
import solutions.shapeit.wethrive.device.repository.DeviceRepository;
import solutions.shapeit.wethrive.identity.entity.AppUser;
import solutions.shapeit.wethrive.identity.entity.UserSession;
import solutions.shapeit.wethrive.identity.entity.UsedRefreshToken;
import solutions.shapeit.wethrive.identity.repository.AppUserRepository;
import solutions.shapeit.wethrive.identity.repository.UsedRefreshTokenRepository;
import solutions.shapeit.wethrive.identity.repository.UserSessionRepository;

/**
 * Performs one refresh-token lookup and rotation in a single, short transaction.
 * An empty result is returned only after that transaction ends, releasing any row lock
 * acquired while a concurrent rotation changed the token hash.
 */
@Service
public class RefreshTokenRotationService {
    public record Rotation(AppUser user, UserSession session, Device device,
                           String accessToken, String refreshToken) {}

    private final UserSessionRepository sessions;
    private final UsedRefreshTokenRepository usedRefreshTokens;
    private final DeviceRepository devices;
    private final AppUserRepository users;
    private final SecureTokens tokens;
    private final ApplicationProperties properties;
    private final Clock clock;

    public RefreshTokenRotationService(UserSessionRepository sessions,
                                       UsedRefreshTokenRepository usedRefreshTokens,
                                       DeviceRepository devices, AppUserRepository users,
                                       SecureTokens tokens, ApplicationProperties properties,
                                       Clock clock) {
        this.sessions = sessions;
        this.usedRefreshTokens = usedRefreshTokens;
        this.devices = devices;
        this.users = users;
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<Rotation> rotate(String refreshTokenHash) {
        UserSession session = sessions.findByRefreshTokenHashForUpdate(refreshTokenHash).orElse(null);
        if (session == null) return Optional.empty();

        Instant now = Instant.now(clock);
        if (session.getRevokedAt() != null || !session.getExpiresAt().isAfter(now)) throw unauthorized();
        Device device = devices.findById(session.getDeviceId())
                .filter(value -> value.getRevokedAt() == null).orElseThrow(this::unauthorized);
        AppUser user = users.findById(session.getUserId())
                .filter(AppUser::isEnabled).orElseThrow(this::unauthorized);

        String access = tokens.randomToken();
        String refresh = tokens.randomToken();
        UsedRefreshToken used = new UsedRefreshToken();
        used.setTokenHash(session.getRefreshTokenHash());
        used.setSessionId(session.getId());
        used.setTokenFamilyId(session.getTokenFamilyId());
        used.setUserId(session.getUserId());
        used.setExpiresAt(session.getExpiresAt());
        usedRefreshTokens.save(used);

        session.setPreviousRefreshTokenHash(session.getRefreshTokenHash());
        session.setRefreshTokenHash(tokens.hash(refresh));
        session.setAccessTokenHash(tokens.hash(access));
        session.setAccessExpiresAt(now.plus(properties.security().accessSessionTtl()));
        session.setLastUsedAt(now);
        sessions.save(session);
        device.setLastSeenAt(now);
        devices.save(device);
        return Optional.of(new Rotation(user, session, device, access, refresh));
    }

    private ApiException unauthorized() {
        return new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                "invalid_session", "The session is invalid or expired");
    }
}
