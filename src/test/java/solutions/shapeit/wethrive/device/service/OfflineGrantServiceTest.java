package solutions.shapeit.wethrive.device.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import solutions.shapeit.wethrive.TestProperties;
import solutions.shapeit.wethrive.common.domain.DomainEnums.MembershipStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.Role;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.device.entity.Device;
import solutions.shapeit.wethrive.device.repository.DeviceRepository;
import solutions.shapeit.wethrive.space.entity.SpaceMembership;
import solutions.shapeit.wethrive.space.repository.SpaceMembershipRepository;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;
import tools.jackson.databind.ObjectMapper;

class OfflineGrantServiceTest {
    @Test
    void signsVerifiesAndInvalidatesOnAuthorizationChange() {
        UUID userId = UUID.randomUUID(), deviceId = UUID.randomUUID(), spaceId = UUID.randomUUID();
        SpaceMembership membership = new SpaceMembership(); membership.setId(UUID.randomUUID());
        membership.setUserId(userId); membership.setSpaceId(spaceId); membership.setRole(Role.FINANCE_EDITOR);
        membership.setStatus(MembershipStatus.ACTIVE);
        SpaceMembershipRepository memberships = mock(SpaceMembershipRepository.class);
        when(memberships.findAllByUserIdAndStatusAndDeletedAtIsNull(userId, MembershipStatus.ACTIVE))
                .thenAnswer(ignored -> List.of(membership));
        Device device = new Device(); device.setId(deviceId); device.setUserId(userId); device.setOfflineAccessEnabled(true);
        DeviceRepository devices = mock(DeviceRepository.class);
        when(devices.findByIdAndUserIdAndRevokedAtIsNull(deviceId, userId)).thenReturn(Optional.of(device));
        Clock clock = Clock.fixed(Instant.parse("2026-07-16T10:00:00Z"), ZoneOffset.UTC);
        OfflineGrantService service = new OfflineGrantService(TestProperties.create(), memberships, devices,
                new SpaceAccessService(memberships), new ObjectMapper(), clock);

        var issued = service.issue(userId, deviceId);
        device.setOfflineGrantExpiresAt(issued.expiresAt());
        var verified = service.verify(userId, deviceId, issued.grant());
        assertThat(verified.valid()).isTrue();
        assertThat(verified.algorithm()).isEqualTo("ES256");
        assertThat(verified.spaceAuthorizations()).singleElement().satisfies(entry -> {
            assertThat(entry.role()).isEqualTo(Role.FINANCE_EDITOR);
            assertThat(entry.capabilities()).contains("VIEW", "RECORD_SPENDING", "EDIT_FINANCE", "EXPORT");
        });
        assertThat(service.verificationKey().format()).isEqualTo("spki");

        String[] parts = issued.grant().split("\\.");
        char replacement = parts[2].charAt(0) == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + replacement + parts[2].substring(1);
        assertThatThrownBy(() -> service.verify(userId, deviceId, tampered)).isInstanceOf(ApiException.class);
        membership.setRole(Role.VIEWER);
        membership.setVersion(1);
        assertThatThrownBy(() -> service.verify(userId, deviceId, issued.grant())).isInstanceOf(ApiException.class);
    }
}
