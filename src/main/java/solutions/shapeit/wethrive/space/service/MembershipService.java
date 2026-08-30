package solutions.shapeit.wethrive.space.service;

import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import solutions.shapeit.wethrive.common.domain.DomainChangeRecorder;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncOperationType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.MembershipStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.Role;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.identity.repository.AppUserRepository;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.ChangeRoleRequest;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.MemberResponse;
import solutions.shapeit.wethrive.space.entity.Space;
import solutions.shapeit.wethrive.space.entity.SpaceMembership;
import solutions.shapeit.wethrive.space.repository.SpaceMembershipRepository;
import solutions.shapeit.wethrive.space.repository.SpaceRepository;
import solutions.shapeit.wethrive.space.service.SpaceAccessService.Capability;

@Service
public class MembershipService {
    private final SpaceMembershipRepository memberships;
    private final SpaceRepository spaces;
    private final AppUserRepository users;
    private final SpaceAccessService access;
    private final Clock clock;
    private final ObjectProvider<DomainChangeRecorder> changes;

    public MembershipService(SpaceMembershipRepository memberships, SpaceRepository spaces,
                             AppUserRepository users, SpaceAccessService access,
                             ObjectProvider<DomainChangeRecorder> changes, Clock clock) {
        this.memberships = memberships;
        this.spaces = spaces;
        this.users = users;
        this.access = access;
        this.clock = clock;
        this.changes = changes;
    }

    @Transactional
    public List<MemberResponse> list(UUID spaceId, UUID actorId) {
        access.require(spaceId, actorId, Capability.VIEW);
        return memberships.findAllBySpaceIdAndDeletedAtIsNullOrderByCreatedAt(spaceId).stream().map(this::map).toList();
    }

    @Transactional
    public MemberResponse changeRole(UUID spaceId, UUID membershipId, UUID actorId, ChangeRoleRequest request) {
        SpaceMembership actor = access.require(spaceId, actorId, Capability.MANAGE_MEMBERS);
        SpaceMembership target = requireMember(spaceId, membershipId);
        if (target.getRole() == Role.OWNER || request.role() == Role.OWNER) {
            throw ApiException.badRequest("Ownership changes require the transfer-ownership action");
        }
        if (actor.getRole() == Role.ADMIN && (target.getRole() == Role.ADMIN || request.role() == Role.ADMIN)) {
            throw ApiException.forbidden();
        }
        if (target.getVersion() != request.version()) throw ApiException.conflict("The membership was changed by another user");
        target.setRole(request.role());
        target = memberships.saveAndFlush(target);
        MemberResponse response = map(target);
        record(spaceId, target, SyncOperationType.UPDATE, actorId, false, response);
        return response;
    }

    @Transactional
    public void remove(UUID spaceId, UUID membershipId, UUID actorId) {
        SpaceMembership actor = access.require(spaceId, actorId, Capability.MANAGE_MEMBERS);
        SpaceMembership target = requireMember(spaceId, membershipId);
        if (target.getRole() == Role.OWNER) throw ApiException.badRequest("The owner cannot be removed");
        if (actor.getRole() == Role.ADMIN && target.getRole() == Role.ADMIN) throw ApiException.forbidden();
        target.setStatus(MembershipStatus.REMOVED);
        target.setDeletedAt(Instant.now(clock));
        target = memberships.saveAndFlush(target);
        record(spaceId, target, SyncOperationType.DELETE, actorId, true, null);
    }

    @Transactional
    public void leave(UUID spaceId, UUID actorId) {
        SpaceMembership target = access.require(spaceId, actorId, Capability.VIEW);
        if (target.getRole() == Role.OWNER) throw ApiException.badRequest("Transfer ownership before leaving the household");
        target.setStatus(MembershipStatus.LEFT);
        target.setDeletedAt(Instant.now(clock));
        target = memberships.saveAndFlush(target);
        record(spaceId, target, SyncOperationType.DELETE, actorId, true, null);
    }

    @Transactional
    public void transfer(UUID spaceId, UUID membershipId, UUID actorId) {
        SpaceMembership owner = access.require(spaceId, actorId, Capability.DELETE_SPACE);
        SpaceMembership nextOwner = requireMember(spaceId, membershipId);
        if (nextOwner.getStatus() != MembershipStatus.ACTIVE || nextOwner.getUserId().equals(actorId)) {
            throw ApiException.badRequest("Select another active household member");
        }
        Space space = spaces.findByIdAndDeletedAtIsNull(spaceId).orElseThrow(() -> ApiException.notFound("Space"));
        owner.setRole(Role.ADMIN);
        nextOwner.setRole(Role.OWNER);
        space.setOwnerUserId(nextOwner.getUserId());
        owner = memberships.saveAndFlush(owner);
        nextOwner = memberships.saveAndFlush(nextOwner);
        space = spaces.saveAndFlush(space);
        record(spaceId, owner, SyncOperationType.UPDATE, actorId, false, map(owner));
        record(spaceId, nextOwner, SyncOperationType.UPDATE, actorId, false, map(nextOwner));
        Space savedSpace = space;
        changes.orderedStream().forEach(recorder -> recorder.record(spaceId, "SPACE", spaceId,
                SyncOperationType.UPDATE, savedSpace.getVersion(), actorId, false,
                java.util.Map.of("id", spaceId, "ownerUserId", savedSpace.getOwnerUserId(), "version", savedSpace.getVersion())));
    }

    private SpaceMembership requireMember(UUID spaceId, UUID memberId) {
        return memberships.findById(memberId).filter(m -> m.getSpaceId().equals(spaceId) && m.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Membership"));
    }

    private MemberResponse map(SpaceMembership membership) {
        String displayName = users.findById(membership.getUserId()).map(u -> u.getDisplayName()).orElse("Former member");
        return new MemberResponse(membership.getId(), membership.getSpaceId(), membership.getUserId(), displayName, membership.getRole(),
                membership.getStatus(), membership.getJoinedAt(), membership.getVersion());
    }
    private void record(UUID spaceId, SpaceMembership membership, SyncOperationType operation, UUID actor,
                        boolean tombstone, Object payload) {
        changes.orderedStream().forEach(recorder -> recorder.record(spaceId, "MEMBERSHIP", membership.getId(),
                operation, membership.getVersion(), actor, tombstone, payload));
    }
}
