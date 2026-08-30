package solutions.shapeit.wethrive.identity.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import solutions.shapeit.wethrive.common.domain.DomainEnums.Theme;
import solutions.shapeit.wethrive.common.domain.DomainEnums.Palette;
import solutions.shapeit.wethrive.common.domain.DomainChangeRecorder;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.identity.dto.SettingsDtos.SettingsRequest;
import solutions.shapeit.wethrive.identity.entity.UserSettings;
import solutions.shapeit.wethrive.identity.repository.UserSettingsRepository;
import solutions.shapeit.wethrive.space.repository.SpaceMembershipRepository;
import solutions.shapeit.wethrive.space.repository.SpaceRepository;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;

class SettingsServiceValidationTest {
    @Test
    void rejectsUnknownCurrencyInvalidLocaleAndInvalidIanaZone() {
        UUID userId = UUID.randomUUID();
        UserSettingsRepository repository = mock(UserSettingsRepository.class);
        when(repository.findByUserId(userId)).thenReturn(Optional.of(new UserSettings()));
        // Java erases ObjectProvider's generic parameter; Mockito can only
        // receive the raw class token even though the variable remains typed.
        @SuppressWarnings("unchecked")
        ObjectProvider<DomainChangeRecorder> changes = mock(ObjectProvider.class);
        SettingsService service = new SettingsService(repository, mock(SpaceAccessService.class),
                mock(SpaceMembershipRepository.class), mock(SpaceRepository.class), changes);
        assertThatThrownBy(() -> service.update(userId, request("ZZZ", "en-ZA", "Africa/Johannesburg")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.update(userId, request("ZAR", "not_valid", "Africa/Johannesburg")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.update(userId, request("ZAR", "en-ZA", "Africa/Cape_Town")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void paletteDefaultsToOriginalAndLegacyUpdatesPreserveAnExplicitMonochromeChoice() {
        UUID userId = UUID.randomUUID();
        UserSettings entity = new UserSettings();
        entity.setPalette(Palette.MONOCHROME);
        UserSettingsRepository repository = mock(UserSettingsRepository.class);
        when(repository.findByUserId(userId)).thenReturn(Optional.of(entity));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        @SuppressWarnings("unchecked")
        ObjectProvider<DomainChangeRecorder> changes = mock(ObjectProvider.class);
        when(changes.orderedStream()).thenAnswer(invocation -> Stream.empty());
        SpaceMembershipRepository memberships = mock(SpaceMembershipRepository.class);
        when(memberships.findAllByUserIdAndStatusAndDeletedAtIsNull(any(), any())).thenReturn(List.of());
        SettingsService service = new SettingsService(repository, mock(SpaceAccessService.class), memberships,
                mock(SpaceRepository.class), changes);

        var legacyResponse = service.update(userId, request("ZAR", "en-ZA", "Africa/Johannesburg"));

        assertThat(new UserSettings().getPalette()).isEqualTo(Palette.ORIGINAL);
        assertThat(legacyResponse.palette()).isEqualTo(Palette.MONOCHROME);

        SettingsRequest explicitOriginal = new SettingsRequest("ZAR", "en-ZA", "Africa/Johannesburg", 1,
                false, new BigDecimal("0.1"), new BigDecimal("0.75"), new BigDecimal("0.90"),
                Theme.SYSTEM, true, false, null, 5, false, null, Palette.ORIGINAL, 0L);
        assertThat(service.update(userId, explicitOriginal).palette()).isEqualTo(Palette.ORIGINAL);
    }

    private SettingsRequest request(String currency, String locale, String zone) {
        return new SettingsRequest(currency, locale, zone, 1, false, new BigDecimal("0.1"),
                new BigDecimal("0.75"), new BigDecimal("0.90"), Theme.SYSTEM, true, false,
                null, 5, false, null, 0);
    }
}
