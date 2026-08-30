package solutions.shapeit.wethrive.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import solutions.shapeit.wethrive.TestProperties;
import solutions.shapeit.wethrive.common.domain.DomainChangeRecorder;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.device.repository.DeviceRepository;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.LoginRequest;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.RegisterRequest;
import solutions.shapeit.wethrive.identity.repository.AccountTokenRepository;
import solutions.shapeit.wethrive.identity.repository.AppUserRepository;
import solutions.shapeit.wethrive.identity.repository.UserSessionRepository;
import solutions.shapeit.wethrive.identity.repository.UserSettingsRepository;
import solutions.shapeit.wethrive.notification.repository.PushSubscriptionRepository;
import solutions.shapeit.wethrive.reminder.repository.ReminderPreferenceRepository;
import solutions.shapeit.wethrive.space.repository.SpaceMembershipRepository;
import solutions.shapeit.wethrive.space.repository.SpaceRepository;
import solutions.shapeit.wethrive.space.service.InvitationService;
import solutions.shapeit.wethrive.space.service.SpaceService;

class AuthRegistrationPrivacyTest {
    @Test
    void existingEmailReturnsTheGenericRegistrationContractWithoutSendingMail() {
        AppUserRepository users = mock(AppUserRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        AccountNotificationService notifications = mock(AccountNotificationService.class);
        when(users.existsByNormalizedEmail("known@example.test")).thenReturn(true);
        when(passwords.encode("StrongPassword123")).thenReturn("unused-dummy-hash");

        @SuppressWarnings("unchecked") ObjectProvider<DomainChangeRecorder> changes = mock(ObjectProvider.class);
        AuthService service = new AuthService(
                users,
                mock(UserSettingsRepository.class),
                mock(UserSessionRepository.class),
                mock(AccountTokenRepository.class),
                mock(RefreshTokenRotationService.class),
                mock(RefreshReuseRevocationService.class),
                mock(DeviceRepository.class),
                mock(SpaceRepository.class),
                mock(PushSubscriptionRepository.class),
                mock(ReminderPreferenceRepository.class),
                mock(SpaceMembershipRepository.class),
                changes,
                mock(SpaceService.class),
                mock(InvitationService.class),
                notifications,
                mock(SecureTokens.class),
                passwords,
                TestProperties.create(),
                Clock.systemUTC());

        var response = service.register(new RegisterRequest(
                "Known@Example.Test", "StrongPassword123", "Known Person", null));

        assertThat(response.message()).isEqualTo(
                "If registration can be completed, verification instructions will be sent");
        assertThat(response.verificationToken()).isNull();
        verify(passwords).encode("StrongPassword123");
        verifyNoInteractions(notifications);
    }

    @Test
    void unknownLoginStillExecutesOneCostTwelvePasswordVerification() {
        AppUserRepository users = mock(AppUserRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        when(users.findByNormalizedEmailAndDeletedAtIsNull("missing@example.test"))
                .thenReturn(Optional.empty());

        @SuppressWarnings("unchecked") ObjectProvider<DomainChangeRecorder> changes = mock(ObjectProvider.class);
        AuthService service = new AuthService(
                users,
                mock(UserSettingsRepository.class),
                mock(UserSessionRepository.class),
                mock(AccountTokenRepository.class),
                mock(RefreshTokenRotationService.class),
                mock(RefreshReuseRevocationService.class),
                mock(DeviceRepository.class),
                mock(SpaceRepository.class),
                mock(PushSubscriptionRepository.class),
                mock(ReminderPreferenceRepository.class),
                mock(SpaceMembershipRepository.class),
                changes,
                mock(SpaceService.class),
                mock(InvitationService.class),
                mock(AccountNotificationService.class),
                mock(SecureTokens.class),
                passwords,
                TestProperties.create(),
                Clock.systemUTC());

        assertThatThrownBy(() -> service.login(new LoginRequest(
                "Missing@Example.Test", "WrongPassword123", null, "Browser", null, null), "test-agent"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Email or password is incorrect");

        ArgumentCaptor<String> dummyHash = ArgumentCaptor.forClass(String.class);
        verify(passwords).matches(eq("WrongPassword123"), dummyHash.capture());
        assertThat(dummyHash.getValue()).startsWith("$2a$12$").hasSize(60);
    }
}
