package solutions.shapeit.wethrive.identity.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import solutions.shapeit.wethrive.common.domain.DomainEnums.BudgetItemView;
import solutions.shapeit.wethrive.common.domain.DomainEnums.Palette;
import solutions.shapeit.wethrive.common.domain.DomainEnums.Theme;

public final class SettingsDtos {
    private SettingsDtos() {}

    public record SettingsRequest(@NotBlank @Size(min = 3, max = 3) String currencyCode,
                                  @NotBlank @Size(max = 35) String locale,
                                  @NotBlank @Size(max = 60) String timeZone,
                                  @Min(1) @Max(7) int weekStartDay,
                                  boolean defaultTitheEnabled,
                                  @DecimalMin("0") @DecimalMax("1") BigDecimal defaultTitheRate,
                                  @DecimalMin("0") @DecimalMax("2") BigDecimal warningThreshold,
                                  @DecimalMin("0") @DecimalMax("2") BigDecimal criticalThreshold,
                                  @NotNull Theme theme, boolean notificationsEnabled,
                                  boolean detailedNotificationsEnabled, UUID defaultSpaceId,
                                  @Min(1) @Max(1440) int autoLockMinutes, boolean onboardingComplete,
                                  BudgetItemView budgetItemView, Palette palette, @NotNull Long version) {
        /** Compatibility constructor for settings payloads captured before palette selection existed. */
        public SettingsRequest(String currencyCode, String locale, String timeZone, int weekStartDay,
                               boolean defaultTitheEnabled, BigDecimal defaultTitheRate,
                               BigDecimal warningThreshold, BigDecimal criticalThreshold, Theme theme,
                               boolean notificationsEnabled, boolean detailedNotificationsEnabled,
                               UUID defaultSpaceId, int autoLockMinutes, boolean onboardingComplete,
                               BudgetItemView budgetItemView, long version) {
            this(currencyCode, locale, timeZone, weekStartDay, defaultTitheEnabled, defaultTitheRate,
                    warningThreshold, criticalThreshold, theme, notificationsEnabled,
                    detailedNotificationsEnabled, defaultSpaceId, autoLockMinutes, onboardingComplete,
                    budgetItemView, null, version);
        }
    }
    public record SettingsResponse(UUID id, String currencyCode, String locale, String timeZone,
                                   int weekStartDay, boolean defaultTitheEnabled, BigDecimal defaultTitheRate,
                                   BigDecimal warningThreshold, BigDecimal criticalThreshold, Theme theme,
                                   Palette palette,
                                   boolean notificationsEnabled, boolean detailedNotificationsEnabled,
                                   UUID defaultSpaceId, int autoLockMinutes, boolean onboardingComplete,
                                   BudgetItemView budgetItemView, long version) {}
}
