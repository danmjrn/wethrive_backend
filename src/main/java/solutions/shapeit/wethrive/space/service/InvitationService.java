package solutions.shapeit.wethrive.space.service;

import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import solutions.shapeit.wethrive.common.domain.DomainEnums.MembershipStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.Role;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.identity.entity.AppUser;
import solutions.shapeit.wethrive.identity.repository.AppUserRepository;
import solutions.shapeit.wethrive.identity.service.SecureTokens;
import solutions.shapeit.wethrive.identity.service.AccountNotificationService;
import solutions.shapeit.wethrive.common.config.ApplicationProperties;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.InvitationRequest;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.InvitationResponse;
import solutions.shapeit.wethrive.space.entity.Invitation;
import solutions.shapeit.wethrive.space.entity.SpaceMembership;
import solutions.shapeit.wethrive.space.repository.InvitationRepository;
import solutions.shapeit.wethrive.space.repository.SpaceMembershipRepository;
import solutions.shapeit.wethrive.space.service.SpaceAccessService.Capability;
import org.springframework.beans.factory.ObjectProvider;
import solutions.shapeit.wethrive.common.domain.DomainChangeRecorder;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncOperationType;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.MemberResponse;

@Service
public class InvitationService {
    private static final Duration INVITATION_TTL = Duration.ofDays(7);
    private final InvitationRepository invitations;
    private final SpaceMembershipRepository memberships;
    private final AppUserRepository users;
    private final SpaceAccessService access;
    private final SecureTokens tokens;
    private final AccountNotificationService notifications;
    private final ApplicationProperties properties;
    private final ObjectProvider<DomainChangeRecorder> changes;
    private final Clock clock;

    public InvitationService(InvitationRepository invitations, SpaceMembershipRepository memberships,
                             AppUserRepository users, SpaceAccessService access, SecureTokens tokens,
                             AccountNotificationService notifications, ApplicationProperties properties,
                             ObjectProvider<DomainChangeRecorder> changes, Clock clock) {
        this.invitations = invitations;
        this.memberships = memberships;
        this.users = users;
        this.access = access;
        this.tokens = tokens;
        this.notifications = notifications;
        this.properties = properties;
        this.changes = changes;
        this.clock = clock;
    }

    @Transactional
    public InvitationResponse create(UUID spaceId, UUID actorId, InvitationRequest request) {
        SpaceMembership actor = access.require(spaceId, actorId, Capability.MANAGE_MEMBERS);
        validateAssignable(actor.getRole(), request.role());
        String normalized = normalize(request.email());
        users.findByNormalizedEmailAndDeletedAtIsNull(normalized).ifPresent(user -> memberships
                .findBySpaceIdAndUserIdAndStatusAndDeletedAtIsNull(spaceId, user.getId(), MembershipStatus.ACTIVE)
                .ifPresent(existing -> { throw ApiException.conflict("This person is already a member"); }));
        String rawToken = tokens.randomToken();
        Invitation invitation = new Invitation();
        if (request.id() != null) {
            if (invitations.existsById(request.id())) throw ApiException.conflict("An invitation with this identifier already exists");
            invitation.setId(request.id());
        }
        invitation.setSpaceId(spaceId);
        invitation.setEmail(request.email().trim());
        invitation.setNormalizedEmail(normalized);
        invitation.setRole(request.role());
        invitation.setTokenHash(tokens.hash(rawToken));
        invitation.setInvitedByUserId(actorId);
        invitation.setExpiresAt(Instant.now(clock).plus(INVITATION_TTL));
        invitation = invitations.saveAndFlush(invitation);
        notifications.sendInvitation(invitation.getEmail(), rawToken);
        record(invitation, SyncOperationType.CREATE, actorId, false, map(invitation, null));
        return map(invitation, exposed(rawToken));
    }

    @Transactional
    public List<InvitationResponse> list(UUID spaceId, UUID actorId) {
        access.require(spaceId, actorId, Capability.MANAGE_MEMBERS);
        return invitations.findAllBySpaceIdOrderByCreatedAtDesc(spaceId).stream().map(i -> map(i, null)).toList();
    }

    @Transactional
    public InvitationResponse resend(UUID spaceId, UUID invitationId, UUID actorId) {
        SpaceMembership actor = access.require(spaceId, actorId, Capability.MANAGE_MEMBERS);
        Invitation invitation = requireInvitation(spaceId, invitationId);
        validateAssignable(actor.getRole(), invitation.getRole());
        if (invitation.getAcceptedAt() != null) throw ApiException.conflict("The invitation has already been accepted");
        String rawToken = tokens.randomToken();
        invitation.setTokenHash(tokens.hash(rawToken));
        invitation.setExpiresAt(Instant.now(clock).plus(INVITATION_TTL));
        invitation.setRevokedAt(null);
        invitation.setDeclinedAt(null);
        invitation = invitations.saveAndFlush(invitation);
        notifications.sendInvitation(invitation.getEmail(), rawToken);
        record(invitation, SyncOperationType.UPDATE, actorId, false, map(invitation, null));
        return map(invitation, exposed(rawToken));
    }

    @Transactional
    public void revoke(UUID spaceId, UUID invitationId, UUID actorId) {
        access.require(spaceId, actorId, Capability.MANAGE_MEMBERS);
        Invitation invitation = requireInvitation(spaceId, invitationId);
        if (invitation.getAcceptedAt() != null) throw ApiException.conflict("The invitation has already been accepted");
        invitation.setRevokedAt(Instant.now(clock));
        invitation = invitations.saveAndFlush(invitation);
        record(invitation, SyncOperationType.DELETE, actorId, true, null);
    }

    @Transactional
    public void accept(String rawToken, UUID userId) {
        AppUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        acceptForUser(rawToken, user);
    }

    @Transactional
    public void acceptForUser(String rawToken, AppUser user) {
        Invitation invitation = requireUsable(rawToken, user.getNormalizedEmail());
        SpaceMembership member = memberships.findBySpaceIdAndUserIdAndDeletedAtIsNull(invitation.getSpaceId(), user.getId())
                .orElseGet(SpaceMembership::new);
        if (member.getId() == null) {
            member.setSpaceId(invitation.getSpaceId());
            member.setUserId(user.getId());
            member.setInvitedByUserId(invitation.getInvitedByUserId());
        }
        member.setRole(invitation.getRole());
        member.setStatus(MembershipStatus.ACTIVE);
        member.setJoinedAt(Instant.now(clock));
        member.setDeletedAt(null);
        member = memberships.saveAndFlush(member);
        invitation.setAcceptedAt(Instant.now(clock));
        invitation = invitations.saveAndFlush(invitation);
        MemberResponse response = new MemberResponse(member.getId(), member.getSpaceId(), user.getId(),
                user.getDisplayName(), member.getRole(), member.getStatus(), member.getJoinedAt(), member.getVersion());
        SpaceMembership saved = member;
        changes.orderedStream().forEach(recorder -> recorder.record(saved.getSpaceId(), "MEMBERSHIP", saved.getId(),
                SyncOperationType.CREATE, saved.getVersion(), user.getId(), false, response));
        record(invitation, SyncOperationType.UPDATE, user.getId(), false, map(invitation, null));
    }

    @Transactional
    public void decline(String rawToken, UUID userId) {
        AppUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        Invitation invitation = requireUsable(rawToken, user.getNormalizedEmail());
        invitation.setDeclinedAt(Instant.now(clock));
        invitation = invitations.saveAndFlush(invitation);
        record(invitation, SyncOperationType.UPDATE, userId, false, map(invitation, null));
    }

    @Transactional
    public boolean isUsableForEmail(String rawToken, String email) {
        try {
            requireUsable(rawToken, normalize(email));
            return true;
        } catch (ApiException ex) {
            return false;
        }
    }

    private Invitation requireUsable(String rawToken, String normalizedEmail) {
        Invitation invitation = invitations.findByTokenHash(tokens.hash(rawToken))
                .orElseThrow(() -> ApiException.badRequest("The invitation is invalid or no longer available"));
        Instant now = Instant.now(clock);
        if (!tokens.constantTimeEquals(invitation.getNormalizedEmail(), normalizedEmail)
                || invitation.getExpiresAt().isBefore(now) || invitation.getAcceptedAt() != null
                || invitation.getDeclinedAt() != null || invitation.getRevokedAt() != null) {
            throw ApiException.badRequest("The invitation is invalid or no longer available");
        }
        return invitation;
    }

    private Invitation requireInvitation(UUID spaceId, UUID invitationId) {
        return invitations.findByIdAndSpaceId(invitationId, spaceId)
                .orElseThrow(() -> ApiException.notFound("Invitation"));
    }

    private void validateAssignable(Role actorRole, Role invitedRole) {
        if (invitedRole == Role.OWNER || (actorRole == Role.ADMIN && invitedRole == Role.ADMIN)) throw ApiException.forbidden();
    }

    private String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }
    private String exposed(String rawToken) { return properties.security().exposeAccountTokens() ? rawToken : null; }

    private InvitationResponse map(Invitation invitation, String rawToken) {
        return new InvitationResponse(invitation.getId(), invitation.getSpaceId(), invitation.getEmail(),
                invitation.getRole(), invitation.getExpiresAt(), invitation.getAcceptedAt(),
                invitation.getDeclinedAt(), invitation.getRevokedAt(), rawToken, invitation.getVersion());
    }
    private void record(Invitation invitation, SyncOperationType operation, UUID actor, boolean tombstone, Object payload) {
        changes.orderedStream().forEach(recorder -> recorder.record(invitation.getSpaceId(), "INVITATION",
                invitation.getId(), operation, invitation.getVersion(), actor, tombstone, payload));
    }
}
