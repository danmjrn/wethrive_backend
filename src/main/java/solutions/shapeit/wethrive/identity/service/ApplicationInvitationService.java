package solutions.shapeit.wethrive.identity.service;

import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import solutions.shapeit.wethrive.common.config.ApplicationProperties;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.ApplicationInvitationResponse;
import solutions.shapeit.wethrive.identity.entity.AppUser;
import solutions.shapeit.wethrive.identity.entity.ApplicationInvitation;
import solutions.shapeit.wethrive.identity.repository.AppUserRepository;
import solutions.shapeit.wethrive.identity.repository.ApplicationInvitationRepository;

/** App access is independent from household membership and never grants financial access. */
@Service
public class ApplicationInvitationService {
    private final ApplicationInvitationRepository invitations;
    private final AppUserRepository users;
    private final SecureTokens tokens;
    private final AccountNotificationService notifications;
    private final ApplicationProperties properties;
    private final Clock clock;

    public ApplicationInvitationService(ApplicationInvitationRepository invitations, AppUserRepository users,
                                        SecureTokens tokens, AccountNotificationService notifications,
                                        ApplicationProperties properties, Clock clock) {
        this.invitations = invitations;
        this.users = users;
        this.tokens = tokens;
        this.notifications = notifications;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public List<ApplicationInvitationResponse> list(UUID actorId) {
        requireAdmin(actorId);
        return invitations.findTop100ByOrderByCreatedAtDesc().stream().map(invitation -> map(invitation, null)).toList();
    }

    @Transactional
    public ApplicationInvitationResponse create(UUID actorId, String email) {
        requireAdmin(actorId);
        String raw = tokens.randomToken();
        ApplicationInvitation invitation = new ApplicationInvitation();
        invitation.setEmail(email.trim());
        invitation.setNormalizedEmail(normalize(email));
        invitation.setTokenHash(tokens.hash(raw));
        invitation.setInvitedByUserId(actorId);
        invitation.setExpiresAt(Instant.now(clock).plus(Duration.ofDays(7)));
        invitation = invitations.saveAndFlush(invitation);
        notifications.sendApplicationInvitation(invitation.getEmail(), raw);
        return map(invitation, properties.security().exposeAccountTokens() ? raw : null);
    }

    @Transactional
    public void revoke(UUID actorId, UUID invitationId) {
        requireAdmin(actorId);
        ApplicationInvitation invitation = invitations.findById(invitationId)
                .orElseThrow(() -> ApiException.notFound("Invitation"));
        if (invitation.getAcceptedAt() == null) {
            invitation.setRevokedAt(Instant.now(clock));
            invitations.save(invitation);
        }
    }

    @Transactional
    public boolean isUsableForEmail(String rawToken, String email) {
        if (rawToken == null || rawToken.isBlank()) return false;
        return invitations.findByTokenHash(tokens.hash(rawToken))
                .filter(invitation -> usable(invitation, email)).isPresent();
    }

    @Transactional
    public void accept(String rawToken, AppUser user) {
        ApplicationInvitation invitation = invitations.findByTokenHash(tokens.hash(rawToken))
                .filter(value -> usable(value, user.getEmail()))
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "invitation_invalid", "This invitation is invalid or expired"));
        invitation.setAcceptedAt(Instant.now(clock));
        invitations.save(invitation);
    }

    @Transactional
    public void accept(UUID userId, String rawToken) {
        accept(rawToken, users.findById(userId).orElseThrow(() -> ApiException.notFound("User")));
    }

    @Transactional
    public void decline(UUID userId, String rawToken) {
        AppUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        ApplicationInvitation invitation = invitations.findByTokenHash(tokens.hash(rawToken))
                .filter(value -> usable(value, user.getEmail()))
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "invitation_invalid", "This invitation is invalid or expired"));
        invitation.setRevokedAt(Instant.now(clock));
        invitations.save(invitation);
    }

    private boolean usable(ApplicationInvitation invitation, String email) {
        return invitation.getAcceptedAt() == null && invitation.getRevokedAt() == null
                && invitation.getExpiresAt().isAfter(Instant.now(clock))
                && invitation.getNormalizedEmail().equals(normalize(email));
    }

    private void requireAdmin(UUID actorId) {
        if (!users.findById(actorId).filter(user -> user.isEnabled() && user.getDeletedAt() == null
                && "SUPER_ADMIN".equals(user.getApplicationRole())).isPresent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "admin_required", "Application owner access is required");
        }
    }

    private ApplicationInvitationResponse map(ApplicationInvitation invitation, String rawToken) {
        return new ApplicationInvitationResponse(invitation.getId(), invitation.getEmail(), invitation.getCreatedAt(),
                invitation.getExpiresAt(), invitation.getAcceptedAt(), invitation.getRevokedAt(), rawToken);
    }

    private String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }
}
