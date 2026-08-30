package solutions.shapeit.wethrive.identity.service;

import jakarta.transaction.Transactional;
import java.util.Locale;
import java.time.ZoneId;
import java.util.Currency;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.identity.dto.SettingsDtos.SettingsRequest;
import solutions.shapeit.wethrive.identity.dto.SettingsDtos.SettingsResponse;
import solutions.shapeit.wethrive.identity.entity.UserSettings;
import solutions.shapeit.wethrive.identity.repository.UserSettingsRepository;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;
import solutions.shapeit.wethrive.space.service.SpaceAccessService.Capability;
import solutions.shapeit.wethrive.common.domain.DomainChangeRecorder;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncOperationType;
import solutions.shapeit.wethrive.common.domain.DomainEnums.MembershipStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SpaceType;
import solutions.shapeit.wethrive.space.repository.SpaceMembershipRepository;
import solutions.shapeit.wethrive.space.repository.SpaceRepository;

@Service
public class SettingsService {
    private final UserSettingsRepository settings;
    private final SpaceAccessService access;
    private final SpaceMembershipRepository memberships;
    private final SpaceRepository spaces;
    private final ObjectProvider<DomainChangeRecorder> changes;

    public SettingsService(UserSettingsRepository settings, SpaceAccessService access,
                           SpaceMembershipRepository memberships, SpaceRepository spaces,
                           ObjectProvider<DomainChangeRecorder> changes) {
        this.settings = settings;
        this.access = access;
        this.memberships = memberships;
        this.spaces = spaces;
        this.changes = changes;
    }

    @Transactional
    public SettingsResponse get(UUID userId) { return map(require(userId)); }

    @Transactional
    public SettingsResponse update(UUID userId, SettingsRequest request) {
        UserSettings entity = require(userId);
        if (request.version() == null) throw ApiException.badRequest("version is required");
        if (entity.getVersion() != request.version().longValue()) throw ApiException.conflict("Settings were changed on another device");
        if (request.defaultSpaceId() != null) access.require(request.defaultSpaceId(), userId, Capability.VIEW);
        if (request.warningThreshold().compareTo(request.criticalThreshold()) >= 0) {
            throw ApiException.badRequest("Warning threshold must be lower than critical threshold");
        }
        String currencyCode = request.currencyCode().toUpperCase(Locale.ROOT);
        java.util.Locale requestedLocale;
        try {
            Currency.getInstance(currencyCode);
            ZoneId.of(request.timeZone());
            requestedLocale = new Locale.Builder().setLanguageTag(request.locale()).build();
            if (requestedLocale.getLanguage().isBlank() || "und".equals(requestedLocale.getLanguage())) {
                throw new IllegalArgumentException("Undetermined locale");
            }
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("Currency, locale, and time zone must be valid ISO/BCP-47/IANA identifiers");
        }
        entity.setCurrencyCode(currencyCode);
        entity.setLocale(requestedLocale.toLanguageTag());
        entity.setTimeZone(request.timeZone());
        entity.setWeekStartDay(request.weekStartDay());
        entity.setDefaultTitheEnabled(request.defaultTitheEnabled());
        entity.setDefaultTitheRate(request.defaultTitheRate());
        entity.setWarningThreshold(request.warningThreshold());
        entity.setCriticalThreshold(request.criticalThreshold());
        entity.setTheme(request.theme());
        // A missing value is a legacy queued update; it must not overwrite a newer palette choice.
        if (request.palette() != null) entity.setPalette(request.palette());
        entity.setNotificationsEnabled(request.notificationsEnabled());
        entity.setDetailedNotificationsEnabled(request.detailedNotificationsEnabled());
        entity.setDefaultSpaceId(request.defaultSpaceId());
        entity.setAutoLockMinutes(request.autoLockMinutes());
        entity.setOnboardingComplete(request.onboardingComplete());
        // Nullable on requests so encrypted outbox records captured by 1.0 clients remain valid.
        if (request.budgetItemView() != null) entity.setBudgetItemView(request.budgetItemView());
        entity = settings.saveAndFlush(entity);
        SettingsResponse response = map(entity);
        long settingsVersion = entity.getVersion();
        memberships.findAllByUserIdAndStatusAndDeletedAtIsNull(userId, MembershipStatus.ACTIVE).stream()
                .filter(member -> spaces.findByIdAndDeletedAtIsNull(member.getSpaceId())
                        .filter(space -> space.getType() == SpaceType.PERSONAL).isPresent())
                .forEach(member -> changes.orderedStream().forEach(recorder -> recorder.record(member.getSpaceId(),
                        "USER_SETTINGS", userId, SyncOperationType.UPDATE, settingsVersion, userId, false, response)));
        return response;
    }

    private UserSettings require(UUID userId) { return settings.findByUserId(userId).orElseThrow(() -> ApiException.notFound("Settings")); }
    private SettingsResponse map(UserSettings s) {
        return new SettingsResponse(s.getId(), s.getCurrencyCode(), s.getLocale(), s.getTimeZone(), s.getWeekStartDay(),
                s.isDefaultTitheEnabled(), s.getDefaultTitheRate(), s.getWarningThreshold(), s.getCriticalThreshold(),
                s.getTheme(), s.getPalette(), s.isNotificationsEnabled(), s.isDetailedNotificationsEnabled(), s.getDefaultSpaceId(),
                s.getAutoLockMinutes(), s.isOnboardingComplete(), s.getBudgetItemView(), s.getVersion());
    }
}
