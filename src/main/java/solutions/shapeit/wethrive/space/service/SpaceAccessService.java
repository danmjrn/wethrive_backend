package solutions.shapeit.wethrive.space.service;

import java.util.EnumSet;
import java.util.UUID;
import java.util.List;
import org.springframework.stereotype.Service;
import solutions.shapeit.wethrive.common.domain.DomainEnums.MembershipStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.Role;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.space.entity.SpaceMembership;
import solutions.shapeit.wethrive.space.repository.SpaceMembershipRepository;

@Service
public class SpaceAccessService {
    public enum Capability { VIEW, RECORD_SPENDING, EDIT_FINANCE, EXPORT, MANAGE_MEMBERS, MANAGE_SPACE, DELETE_SPACE }

    private final SpaceMembershipRepository memberships;

    public SpaceAccessService(SpaceMembershipRepository memberships) {
        this.memberships = memberships;
    }

    public SpaceMembership require(UUID spaceId, UUID userId, Capability capability) {
        SpaceMembership membership = memberships
                .findBySpaceIdAndUserIdAndStatusAndDeletedAtIsNull(spaceId, userId, MembershipStatus.ACTIVE)
                .orElseThrow(ApiException::forbidden);
        if (!allowed(membership.getRole(), capability)) throw ApiException.forbidden();
        return membership;
    }

    public boolean can(UUID spaceId, UUID userId, Capability capability) {
        return memberships.findBySpaceIdAndUserIdAndStatusAndDeletedAtIsNull(spaceId, userId, MembershipStatus.ACTIVE)
                .filter(member -> allowed(member.getRole(), capability)).isPresent();
    }

    public List<String> capabilities(Role role) {
        return java.util.Arrays.stream(Capability.values()).filter(capability -> allowed(role, capability))
                .map(Enum::name).sorted().toList();
    }

    private boolean allowed(Role role, Capability capability) {
        return switch (capability) {
            case VIEW -> true;
            case RECORD_SPENDING -> EnumSet.of(Role.OWNER, Role.ADMIN, Role.FINANCE_EDITOR, Role.MEMBER).contains(role);
            case EDIT_FINANCE -> EnumSet.of(Role.OWNER, Role.ADMIN, Role.FINANCE_EDITOR).contains(role);
            case EXPORT -> EnumSet.of(Role.OWNER, Role.ADMIN, Role.FINANCE_EDITOR).contains(role);
            case MANAGE_MEMBERS, MANAGE_SPACE -> EnumSet.of(Role.OWNER, Role.ADMIN).contains(role);
            case DELETE_SPACE -> role == Role.OWNER;
        };
    }
}
