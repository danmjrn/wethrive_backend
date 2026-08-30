package solutions.shapeit.wethrive.identity.service;

import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import solutions.shapeit.wethrive.common.config.ApplicationProperties;
import solutions.shapeit.wethrive.common.domain.DomainEnums.AccountTokenType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.Palette;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.device.entity.Device;
import solutions.shapeit.wethrive.device.repository.DeviceRepository;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.AuthResponse;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.BootstrapRequest;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.ChangePasswordRequest;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.LoginRequest;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.RegisterRequest;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.RegistrationResponse;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.SessionResponse;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.UserResponse;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.UpdateProfileRequest;
import solutions.shapeit.wethrive.identity.entity.AccountToken;
import solutions.shapeit.wethrive.identity.entity.AppUser;
import solutions.shapeit.wethrive.identity.entity.UserSession;
import solutions.shapeit.wethrive.identity.entity.UserSettings;
import solutions.shapeit.wethrive.identity.repository.AccountTokenRepository;
import solutions.shapeit.wethrive.identity.repository.AppUserRepository;
import solutions.shapeit.wethrive.identity.repository.UserSessionRepository;
import solutions.shapeit.wethrive.identity.repository.UserSettingsRepository;
import solutions.shapeit.wethrive.space.service.InvitationService;
import solutions.shapeit.wethrive.space.service.SpaceService;
import solutions.shapeit.wethrive.space.repository.SpaceRepository;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SpaceType;
import solutions.shapeit.wethrive.notification.repository.PushSubscriptionRepository;
import solutions.shapeit.wethrive.reminder.repository.ReminderPreferenceRepository;
import solutions.shapeit.wethrive.space.repository.SpaceMembershipRepository;
import solutions.shapeit.wethrive.common.domain.DomainChangeRecorder;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncOperationType;
import org.springframework.beans.factory.ObjectProvider;

@Service
public class AuthService {
    public record SessionIssue(AuthResponse response, String accessToken, String refreshToken) {}

    /**
     * A valid cost-12 BCrypt value used only when no account matches a login.
     * Running the same password verification work on misses prevents the user
     * repository result from becoming a practical email-enumeration timer.
     */
    private static final String DUMMY_LOGIN_PASSWORD_HASH =
            "$2a$12$IEM8B5ABbF4Q7yT8eDEqF.PcErzm9G.B4y.i87juHF3AK74ZgkJSq";

    private final AppUserRepository users;
    private final UserSettingsRepository settings;
    private final UserSessionRepository sessions;
    private final AccountTokenRepository accountTokens;
    private final RefreshTokenRotationService refreshTokenRotation;
    private final RefreshReuseRevocationService refreshReuseRevocation;
    private final DeviceRepository devices;
    private final SpaceRepository spaceRepository;
    private final PushSubscriptionRepository pushSubscriptions;
    private final ReminderPreferenceRepository reminderPreferences;
    private final SpaceMembershipRepository memberships;
    private final ObjectProvider<DomainChangeRecorder> changes;
    private final SpaceService spaceService;
    private final InvitationService invitationService;
    private final AccountNotificationService notifications;
    private final SecureTokens tokens;
    private final PasswordEncoder passwords;
    private final ApplicationProperties properties;
    private final Clock clock;

    public AuthService(AppUserRepository users, UserSettingsRepository settings, UserSessionRepository sessions,
                       AccountTokenRepository accountTokens, RefreshTokenRotationService refreshTokenRotation,
                       RefreshReuseRevocationService refreshReuseRevocation,
                       DeviceRepository devices, SpaceRepository spaceRepository,
                       PushSubscriptionRepository pushSubscriptions, ReminderPreferenceRepository reminderPreferences,
                       SpaceMembershipRepository memberships, ObjectProvider<DomainChangeRecorder> changes,
                       SpaceService spaceService,
                       InvitationService invitationService, AccountNotificationService notifications,
                       SecureTokens tokens, PasswordEncoder passwords, ApplicationProperties properties, Clock clock) {
        this.users = users;
        this.settings = settings;
        this.sessions = sessions;
        this.accountTokens = accountTokens;
        this.refreshTokenRotation = refreshTokenRotation;
        this.refreshReuseRevocation = refreshReuseRevocation;
        this.devices = devices;
        this.spaceRepository = spaceRepository;
        this.pushSubscriptions = pushSubscriptions;
        this.reminderPreferences = reminderPreferences;
        this.memberships = memberships;
        this.changes = changes;
        this.spaceService = spaceService;
        this.invitationService = invitationService;
        this.notifications = notifications;
        this.tokens = tokens;
        this.passwords = passwords;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public RegistrationResponse register(RegisterRequest request) {
        String mode = properties.security().registrationMode().toUpperCase(Locale.ROOT);
        if ("CLOSED".equals(mode)) throw new ApiException(HttpStatus.FORBIDDEN, "registration_closed", "Registration is currently closed");
        if ("INVITE_ONLY".equals(mode) && (request.invitationToken() == null
                || !invitationService.isUsableForEmail(request.invitationToken(), request.email()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "invitation_required", "A valid invitation is required");
        }
        validatePassword(request.password());
        if (users.existsByNormalizedEmail(normalize(request.email()))) {
            // Keep the public response and the expensive password-hash work the
            // same as a new registration so this endpoint cannot be used to
            // discover whether an email address has an account.
            passwords.encode(request.password());
            return new RegistrationResponse("If registration can be completed, verification instructions will be sent", null);
        }
        AppUser user = createUser(request.email(), request.password(), request.displayName(), false);
        String rawToken = issueAccountToken(user.getId(), AccountTokenType.EMAIL_VERIFICATION, Duration.ofHours(24));
        notifications.sendVerification(user.getEmail(), rawToken);
        if (request.invitationToken() != null) invitationService.acceptForUser(request.invitationToken(), user);
        return new RegistrationResponse("If registration can be completed, verification instructions will be sent",
                properties.security().exposeAccountTokens() ? rawToken : null);
    }

    @Transactional
    public SessionIssue bootstrap(BootstrapRequest request, String userAgent) {
        if (users.count() != 0) throw ApiException.conflict("Bootstrap has already been completed");
        if (properties.security().bootstrapToken() == null || properties.security().bootstrapToken().isBlank()
                || !tokens.constantTimeEquals(properties.security().bootstrapToken(), request.bootstrapToken())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "bootstrap_denied", "Bootstrap credentials are invalid");
        }
        validatePassword(request.password());
        AppUser user = createUser(request.email(), request.password(), request.displayName(), true);
        LoginRequest login = new LoginRequest(request.email(), request.password(), request.deviceId(), request.deviceName(), null, null);
        return createSession(user, login, userAgent);
    }

    @Transactional
    public SessionIssue login(LoginRequest request, String userAgent) {
        AppUser user = users.findByNormalizedEmailAndDeletedAtIsNull(normalize(request.email()))
                .orElse(null);
        String passwordHash = user == null ? DUMMY_LOGIN_PASSWORD_HASH : user.getPasswordHash();
        boolean passwordMatches = passwords.matches(request.password(), passwordHash);
        if (user == null || !passwordMatches || !user.isEnabled()) throw invalidCredentials();
        if (!user.isEmailVerified()) throw new ApiException(HttpStatus.FORBIDDEN, "email_not_verified", "Verify your email before signing in");
        return createSession(user, request, userAgent);
    }

    public SessionIssue refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) throw unauthorized();
        String hash = tokens.hash(rawRefreshToken);
        var rotation = refreshTokenRotation.rotate(hash);
        if (rotation.isEmpty()) {
            refreshReuseRevocation.revokeIfReused(hash);
            throw unauthorized();
        }
        var value = rotation.get();
        return new SessionIssue(map(value.user(), value.session(), value.device()),
                value.accessToken(), value.refreshToken());
    }

    @Transactional
    public void logout(String rawAccess, String rawRefresh) {
        UserSession session = findSession(rawAccess, rawRefresh);
        if (session != null) {
            session.setRevokedAt(Instant.now(clock));
            sessions.save(session);
        }
    }

    @Transactional
    public UserPrincipal authenticateAccessToken(String rawAccessToken) {
        if (rawAccessToken == null || rawAccessToken.isBlank()) return null;
        Instant now = Instant.now(clock);
        return sessions.findByAccessTokenHashAndRevokedAtIsNullAndAccessExpiresAtAfter(tokens.hash(rawAccessToken), now)
                .flatMap(session -> users.findById(session.getUserId()))
                .filter(user -> user.isEnabled() && user.getDeletedAt() == null)
                .map(UserPrincipal::from).orElse(null);
    }

    @Transactional
    public UserResponse current(UUID userId) { return map(requireUser(userId)); }

    @Transactional
    public AuthResponse currentSession(UUID userId, String rawAccessToken) {
        if (rawAccessToken == null || rawAccessToken.isBlank()) throw unauthorized();
        Instant now = Instant.now(clock);
        UserSession session = sessions.findByAccessTokenHashAndRevokedAtIsNullAndAccessExpiresAtAfter(
                        tokens.hash(rawAccessToken), now)
                .filter(value -> value.getUserId().equals(userId))
                .orElseThrow(this::unauthorized);
        Device device = devices.findByIdAndUserIdAndRevokedAtIsNull(session.getDeviceId(), userId)
                .orElseThrow(this::unauthorized);
        return map(requireUser(userId), session, device);
    }

    @Transactional
    public UserResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        AppUser user = requireUser(userId);
        if (user.getVersion() != request.version()) throw ApiException.conflict("Profile was changed on another device");
        user.setDisplayName(request.displayName().trim());
        user.setAvatarReference(request.avatarReference() == null || request.avatarReference().isBlank()
                ? null : request.avatarReference().trim());
        user = users.saveAndFlush(user);
        UserResponse response = map(user);
        long profileVersion = user.getVersion();
        memberships.findAllByUserIdAndStatusAndDeletedAtIsNull(userId, solutions.shapeit.wethrive.common.domain.DomainEnums.MembershipStatus.ACTIVE)
                .forEach(member -> changes.orderedStream().forEach(recorder -> recorder.record(member.getSpaceId(),
                        "USER_PROFILE", userId, SyncOperationType.UPDATE, profileVersion, userId, false, response)));
        return response;
    }

    @Transactional
    public List<SessionResponse> sessions(UUID userId, String currentAccessToken) {
        String currentHash = currentAccessToken == null ? "" : tokens.hash(currentAccessToken);
        return sessions.findAllByUserIdAndRevokedAtIsNullOrderByLastUsedAtDesc(userId).stream()
                .map(session -> new SessionResponse(session.getId(), session.getDeviceId(),
                        devices.findById(session.getDeviceId()).map(Device::getDisplayName).orElse("Unknown device"),
                        session.getIssuedAt(), session.getExpiresAt(), session.getLastUsedAt(),
                        tokens.constantTimeEquals(session.getAccessTokenHash(), currentHash))).toList();
    }

    @Transactional
    public void revokeSession(UUID userId, UUID sessionId) {
        UserSession session = sessions.findById(sessionId).filter(s -> s.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Session"));
        session.setRevokedAt(Instant.now(clock));
        sessions.save(session);
    }

    @Transactional
    public void revokeOtherSessions(UUID userId, String currentAccessToken) {
        String currentHash = tokens.hash(currentAccessToken == null ? "" : currentAccessToken);
        sessions.findAllByUserIdAndRevokedAtIsNullOrderByLastUsedAtDesc(userId).stream()
                .filter(s -> !tokens.constantTimeEquals(s.getAccessTokenHash(), currentHash)).forEach(s -> {
                    s.setRevokedAt(Instant.now(clock));
                    sessions.save(s);
                });
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        AccountToken token = requireAccountToken(rawToken, AccountTokenType.EMAIL_VERIFICATION);
        AppUser user = requireUser(token.getUserId());
        user.setEmailVerified(true);
        users.save(user);
        consumeOutstanding(user.getId(), AccountTokenType.EMAIL_VERIFICATION);
    }

    @Transactional
    public String forgotPassword(String email) {
        users.findByNormalizedEmailAndDeletedAtIsNull(normalize(email)).ifPresent(user -> {
            String token = issueAccountToken(user.getId(), AccountTokenType.PASSWORD_RESET, Duration.ofMinutes(30));
            notifications.sendPasswordReset(user.getEmail(), token);
        });
        return null;
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        validatePassword(newPassword);
        AccountToken token = requireAccountToken(rawToken, AccountTokenType.PASSWORD_RESET);
        AppUser user = requireUser(token.getUserId());
        user.setPasswordHash(passwords.encode(newPassword));
        user.setPasswordChangedAt(Instant.now(clock));
        users.save(user);
        consumeOutstanding(user.getId(), AccountTokenType.PASSWORD_RESET);
        revokeAll(user.getId());
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        AppUser user = requireUser(userId);
        if (!passwords.matches(request.currentPassword(), user.getPasswordHash())) throw invalidCredentials();
        validatePassword(request.newPassword());
        user.setPasswordHash(passwords.encode(request.newPassword()));
        user.setPasswordChangedAt(Instant.now(clock));
        users.save(user);
        consumeOutstanding(userId, AccountTokenType.PASSWORD_RESET);
        revokeAll(userId);
    }

    @Transactional
    public void deleteAccount(UUID userId, String password) {
        AppUser user = requireUser(userId);
        if (!passwords.matches(password, user.getPasswordHash())) throw invalidCredentials();
        if (spaceRepository.existsByOwnerUserIdAndTypeAndDeletedAtIsNull(userId, SpaceType.HOUSEHOLD)) {
            throw ApiException.conflict("Transfer or delete every household space you own before deleting your account");
        }
        Instant now = Instant.now(clock);
        user.setEnabled(false);
        user.setDeletedAt(now);
        user.setEmail("deleted+" + user.getId() + "@invalid.local");
        user.setNormalizedEmail(user.getEmail());
        users.save(user);
        revokeAll(userId);
        devices.findAllByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(userId).forEach(device -> {
            device.setOfflineAccessEnabled(false);
            device.setOfflineGrantExpiresAt(null);
            device.setRevokedAt(now);
            devices.save(device);
            pushSubscriptions.findAllByDeviceIdAndRevokedAtIsNull(device.getId()).forEach(subscription -> {
                subscription.setEnabled(false);
                subscription.setRevokedAt(now);
                pushSubscriptions.save(subscription);
            });
        });
        reminderPreferences.findAllByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId).forEach(preference -> {
            preference.setEnabled(false);
            preference.setDeletedAt(now);
            reminderPreferences.save(preference);
        });
        accountTokens.findAllByUserIdAndConsumedAtIsNull(userId).forEach(token -> {
            token.setConsumedAt(now);
            accountTokens.save(token);
        });
        memberships.findAllByUserIdAndStatusAndDeletedAtIsNull(userId,
                solutions.shapeit.wethrive.common.domain.DomainEnums.MembershipStatus.ACTIVE).forEach(membership -> {
            membership.setStatus(solutions.shapeit.wethrive.common.domain.DomainEnums.MembershipStatus.LEFT);
            membership.setDeletedAt(now);
            membership = memberships.saveAndFlush(membership);
            var saved = membership;
            changes.orderedStream().forEach(recorder -> recorder.record(saved.getSpaceId(), "MEMBERSHIP",
                    saved.getId(), SyncOperationType.DELETE, saved.getVersion(), userId, true, null));
        });
    }

    private AppUser createUser(String email, String password, String displayName, boolean verified) {
        String normalized = normalize(email);
        if (users.existsByNormalizedEmail(normalized)) throw ApiException.conflict("An account with this email already exists");
        AppUser user = new AppUser();
        user.setEmail(email.trim());
        user.setNormalizedEmail(normalized);
        user.setPasswordHash(passwords.encode(password));
        user.setDisplayName(displayName.trim());
        user.setEmailVerified(verified);
        user.setPasswordChangedAt(Instant.now(clock));
        users.save(user);
        UserSettings userSettings = new UserSettings();
        userSettings.setUserId(user.getId());
        userSettings.setCurrencyCode(properties.branding().defaultCurrency());
        userSettings.setLocale(properties.branding().defaultLocale());
        userSettings.setTimeZone(properties.branding().defaultTimeZone());
        userSettings.setPalette(Palette.ORIGINAL);
        settings.save(userSettings);
        var personal = spaceService.createPersonalSpace(user);
        userSettings.setDefaultSpaceId(personal.getId());
        settings.save(userSettings);
        return user;
    }

    private SessionIssue createSession(AppUser user, LoginRequest request, String userAgent) {
        Instant now = Instant.now(clock);
        Device device = request.deviceId() == null ? null
                : devices.findByIdAndUserIdAndRevokedAtIsNull(request.deviceId(), user.getId()).orElse(null);
        if (device == null) {
            // Never attach a caller-supplied device UUID that belongs to another
            // account. A known UUID is reused only after its ownership check.
            device = new Device();
            device.setId(UUID.randomUUID());
        }
        device.setUserId(user.getId());
        device.setDisplayName(request.deviceName() == null || request.deviceName().isBlank() ? "Web browser" : request.deviceName().trim());
        device.setPlatform(request.platform());
        device.setBrowser(request.browser());
        device.setLastSeenAt(now);
        devices.save(device);

        String access = tokens.randomToken();
        String refresh = tokens.randomToken();
        UserSession session = new UserSession();
        session.setUserId(user.getId());
        session.setDeviceId(device.getId());
        session.setAccessTokenHash(tokens.hash(access));
        session.setRefreshTokenHash(tokens.hash(refresh));
        session.setTokenFamilyId(UUID.randomUUID());
        session.setIssuedAt(now);
        session.setAccessExpiresAt(now.plus(properties.security().accessSessionTtl()));
        session.setExpiresAt(now.plus(properties.security().refreshSessionTtl()));
        session.setLastUsedAt(now);
        session.setUserAgentMetadata(sanitizeUserAgent(userAgent));
        sessions.save(session);
        return new SessionIssue(map(user, session, device), access, refresh);
    }

    private String issueAccountToken(UUID userId, AccountTokenType type, Duration ttl) {
        consumeOutstanding(userId, type);
        String raw = tokens.randomToken();
        AccountToken token = new AccountToken();
        token.setUserId(userId);
        token.setType(type);
        token.setTokenHash(tokens.hash(raw));
        token.setExpiresAt(Instant.now(clock).plus(ttl));
        accountTokens.save(token);
        return raw;
    }

    private void consumeOutstanding(UUID userId, AccountTokenType type) {
        Instant now = Instant.now(clock);
        accountTokens.findAllByUserIdAndTypeAndConsumedAtIsNull(userId, type).forEach(token -> {
            token.setConsumedAt(now);
            accountTokens.save(token);
        });
    }

    private AccountToken requireAccountToken(String raw, AccountTokenType type) {
        AccountToken token = accountTokens.findByTokenHashAndTypeAndConsumedAtIsNull(tokens.hash(raw), type)
                .orElseThrow(() -> ApiException.badRequest("The token is invalid or has already been used"));
        if (!token.getExpiresAt().isAfter(Instant.now(clock))) throw ApiException.badRequest("The token has expired");
        return token;
    }

    private UserSession findSession(String access, String refresh) {
        Instant now = Instant.now(clock);
        if (access != null) {
            var found = sessions.findByAccessTokenHashAndRevokedAtIsNullAndAccessExpiresAtAfter(tokens.hash(access), now);
            if (found.isPresent()) return found.get();
        }
        return refresh == null ? null : sessions.findByRefreshTokenHash(tokens.hash(refresh)).orElse(null);
    }

    private void revokeAll(UUID userId) {
        sessions.findAllByUserIdAndRevokedAtIsNullOrderByLastUsedAtDesc(userId).forEach(session -> {
            session.setRevokedAt(Instant.now(clock));
            sessions.save(session);
        });
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 72
                || !password.matches(".*[a-z].*") || !password.matches(".*[A-Z].*")
                || !password.matches(".*[0-9].*")) {
            throw ApiException.badRequest("Password must be 12-72 characters and include upper-case, lower-case, and numeric characters");
        }
    }

    private AppUser requireUser(UUID id) { return users.findById(id).orElseThrow(() -> ApiException.notFound("User")); }
    private String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }
    static String sanitizeUserAgent(String value) {
        if (value == null) return null;
        String sanitized = value.replaceAll("[\\r\\n]", " ");
        return sanitized.substring(0, Math.min(255, sanitized.length()));
    }
    private ApiException invalidCredentials() { return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Email or password is incorrect"); }
    private ApiException unauthorized() { return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_session", "The session is invalid or expired"); }
    private UserResponse map(AppUser user) { return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(),
            user.getAvatarReference(), user.isEmailVerified(), user.getCreatedAt(), user.getVersion()); }
    private AuthResponse map(AppUser user, UserSession session, Device device) {
        return new AuthResponse(map(user), session.getId(), device.getId(), session.getAccessExpiresAt(), session.getExpiresAt());
    }
}
