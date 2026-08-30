package solutions.shapeit.wethrive.space.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import solutions.shapeit.wethrive.common.domain.DomainEnums.MembershipStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.Role;
import solutions.shapeit.wethrive.space.entity.SpaceMembership;
import solutions.shapeit.wethrive.space.repository.SpaceMembershipRepository;
import solutions.shapeit.wethrive.space.service.SpaceAccessService.Capability;

class SpaceAccessServiceTest {
    @Test
    void roleMatrixSeparatesRecordingEditingExportAndAdministration() {
        UUID spaceId = UUID.randomUUID(), userId = UUID.randomUUID();
        SpaceMembershipRepository repository = mock(SpaceMembershipRepository.class);
        SpaceMembership membership = new SpaceMembership(); membership.setSpaceId(spaceId); membership.setUserId(userId);
        membership.setStatus(MembershipStatus.ACTIVE);
        when(repository.findBySpaceIdAndUserIdAndStatusAndDeletedAtIsNull(spaceId, userId, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(membership));
        SpaceAccessService service = new SpaceAccessService(repository);

        membership.setRole(Role.MEMBER);
        assertThat(service.can(spaceId, userId, Capability.VIEW)).isTrue();
        assertThat(service.can(spaceId, userId, Capability.RECORD_SPENDING)).isTrue();
        assertThat(service.can(spaceId, userId, Capability.EDIT_FINANCE)).isFalse();
        assertThat(service.can(spaceId, userId, Capability.EXPORT)).isFalse();
        membership.setRole(Role.FINANCE_EDITOR);
        assertThat(service.can(spaceId, userId, Capability.EDIT_FINANCE)).isTrue();
        assertThat(service.can(spaceId, userId, Capability.EXPORT)).isTrue();
        assertThat(service.can(spaceId, userId, Capability.MANAGE_MEMBERS)).isFalse();
        membership.setRole(Role.ADMIN);
        assertThat(service.can(spaceId, userId, Capability.MANAGE_MEMBERS)).isTrue();
        assertThat(service.can(spaceId, userId, Capability.DELETE_SPACE)).isFalse();
        membership.setRole(Role.OWNER);
        assertThat(service.can(spaceId, userId, Capability.DELETE_SPACE)).isTrue();
    }
}
