package solutions.shapeit.wethrive.space.service;

import jakarta.transaction.Transactional;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Currency;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import solutions.shapeit.wethrive.common.config.ApplicationProperties;
import solutions.shapeit.wethrive.common.domain.DomainEnums.MembershipStatus;
import solutions.shapeit.wethrive.common.domain.DomainEnums.Role;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SpaceType;
import solutions.shapeit.wethrive.common.domain.ReferenceDataProvisioner;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.identity.entity.AppUser;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.CreateSpaceRequest;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.SpaceResponse;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.UpdateSpaceRequest;
import solutions.shapeit.wethrive.space.entity.Space;
import solutions.shapeit.wethrive.space.entity.SpaceMembership;
import solutions.shapeit.wethrive.space.repository.SpaceMembershipRepository;
import solutions.shapeit.wethrive.space.repository.SpaceRepository;
import solutions.shapeit.wethrive.space.service.SpaceAccessService.Capability;
import solutions.shapeit.wethrive.common.domain.DomainChangeRecorder;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncOperationType;
import solutions.shapeit.wethrive.identity.repository.AppUserRepository;
import solutions.shapeit.wethrive.space.dto.SpaceDtos.MemberResponse;

@Service
public class SpaceService {
    private final SpaceRepository spaces;
    private final SpaceMembershipRepository memberships;
    private final SpaceAccessService access;
    private final ApplicationProperties properties;
    private final ObjectProvider<ReferenceDataProvisioner> referenceData;
    private final Clock clock;
    private final ObjectProvider<DomainChangeRecorder> changes;
    private final AppUserRepository users;

    public SpaceService(SpaceRepository spaces, SpaceMembershipRepository memberships, SpaceAccessService access,
                        ApplicationProperties properties, ObjectProvider<ReferenceDataProvisioner> referenceData,
                        ObjectProvider<DomainChangeRecorder> changes, AppUserRepository users, Clock clock) {
        this.spaces = spaces;
        this.memberships = memberships;
        this.access = access;
        this.properties = properties;
        this.referenceData = referenceData;
        this.changes = changes;
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    public Space createPersonalSpace(AppUser user) {
        return create(user.getId(), UUID.randomUUID(), user.getDisplayName() + "'s Space", SpaceType.PERSONAL,
                properties.branding().defaultCurrency(), properties.branding().defaultLocale(),
                properties.branding().defaultTimeZone());
    }

    @Transactional
    public SpaceResponse createHousehold(UUID actorId, CreateSpaceRequest request) {
        Space space = create(actorId, request.id(), request.name(), SpaceType.HOUSEHOLD,
                fallback(request.currencyCode(), properties.branding().defaultCurrency()),
                fallback(request.locale(), properties.branding().defaultLocale()),
                fallback(request.timeZone(), properties.branding().defaultTimeZone()));
        return map(space, Role.OWNER);
    }

    @Transactional
    protected Space create(UUID actorId, UUID id, String name, SpaceType type, String currency, String locale, String timeZone) {
        if (spaces.existsById(id)) throw ApiException.conflict("A space with this identifier already exists");
        String currencyCode = currency.toUpperCase(Locale.ROOT);
        Locale parsedLocale;
        try {
            Currency.getInstance(currencyCode);
            ZoneId.of(timeZone);
            parsedLocale = new Locale.Builder().setLanguageTag(locale).build();
            if (parsedLocale.getLanguage().isBlank() || "und".equals(parsedLocale.getLanguage())) throw new IllegalArgumentException();
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("Currency, locale, and time zone must be valid ISO/BCP-47/IANA identifiers");
        }
        Space space = new Space();
        space.setId(id);
        space.setType(type);
        space.setName(name.trim());
        space.setSlug(uniqueSlug(name));
        space.setOwnerUserId(actorId);
        space.setCurrencyCode(currencyCode);
        space.setLocale(parsedLocale.toLanguageTag());
        space.setTimeZone(timeZone);
        spaces.save(space);

        SpaceMembership member = new SpaceMembership();
        member.setSpaceId(space.getId());
        member.setUserId(actorId);
        member.setRole(Role.OWNER);
        member.setStatus(MembershipStatus.ACTIVE);
        member.setJoinedAt(Instant.now(clock));
        memberships.save(member);
        referenceData.orderedStream().forEach(p -> p.initializeForSpace(space.getId(), actorId));
        record(space.getId(), "SPACE", space.getId(), SyncOperationType.CREATE, space.getVersion(), actorId,
                false, map(space, Role.OWNER));
        String displayName = users.findById(actorId).map(AppUser::getDisplayName).orElse("Member");
        record(space.getId(), "MEMBERSHIP", member.getId(), SyncOperationType.CREATE, member.getVersion(), actorId,
                false, new MemberResponse(member.getId(), space.getId(), actorId, displayName, Role.OWNER,
                        MembershipStatus.ACTIVE, member.getJoinedAt(), member.getVersion()));
        return space;
    }

    @Transactional
    public List<SpaceResponse> list(UUID userId) {
        return memberships.findAllByUserIdAndStatusAndDeletedAtIsNull(userId, MembershipStatus.ACTIVE).stream()
                .map(member -> spaces.findByIdAndDeletedAtIsNull(member.getSpaceId())
                        .map(space -> map(space, member.getRole())).orElse(null))
                .filter(java.util.Objects::nonNull).toList();
    }

    @Transactional
    public SpaceResponse get(UUID id, UUID actorId) {
        SpaceMembership member = access.require(id, actorId, Capability.VIEW);
        return map(requireSpace(id), member.getRole());
    }

    @Transactional
    public SpaceResponse update(UUID id, UUID actorId, UpdateSpaceRequest request) {
        SpaceMembership member = access.require(id, actorId, Capability.MANAGE_SPACE);
        Space space = requireSpace(id);
        if (space.getVersion() != request.version()) throw ApiException.conflict("The space was changed by another user");
        space.setName(request.name().trim());
        space = spaces.saveAndFlush(space);
        SpaceResponse response = map(space, member.getRole());
        record(id, "SPACE", id, SyncOperationType.UPDATE, space.getVersion(), actorId, false, response);
        return response;
    }

    @Transactional
    public void delete(UUID id, UUID actorId) {
        access.require(id, actorId, Capability.DELETE_SPACE);
        Space space = requireSpace(id);
        if (space.getType() == SpaceType.PERSONAL) throw ApiException.badRequest("A Personal Space cannot be deleted independently");
        space.setDeletedAt(Instant.now(clock));
        spaces.saveAndFlush(space);
        memberships.findAllBySpaceIdAndDeletedAtIsNullOrderByCreatedAt(id).forEach(member -> {
            member.setStatus(MembershipStatus.REMOVED);
            member.setDeletedAt(Instant.now(clock));
            member = memberships.saveAndFlush(member);
            record(id, "MEMBERSHIP", member.getId(), SyncOperationType.DELETE, member.getVersion(), actorId, true, null);
        });
        record(id, "SPACE", id, SyncOperationType.DELETE, space.getVersion(), actorId, true, null);
    }

    public Space requireSpace(UUID id) {
        return spaces.findByIdAndDeletedAtIsNull(id).orElseThrow(() -> ApiException.notFound("Space"));
    }

    private SpaceResponse map(Space space, Role role) {
        return new SpaceResponse(space.getId(), space.getType(), space.getName(), space.getSlug(),
                space.getOwnerUserId(), space.getCurrencyCode(), space.getLocale(), space.getTimeZone(), role,
                space.getVersion(), space.getCreatedAt(), space.getUpdatedAt());
    }

    private String uniqueSlug(String name) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.isBlank()) base = "space";
        String candidate = base;
        while (spaces.existsBySlug(candidate)) candidate = base + "-" + UUID.randomUUID().toString().substring(0, 8);
        return candidate;
    }

    private String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
    private void record(UUID spaceId, String type, UUID id, SyncOperationType operation, long version,
                        UUID actor, boolean tombstone, Object payload) {
        changes.orderedStream().forEach(recorder -> recorder.record(spaceId, type, id, operation, version, actor, tombstone, payload));
    }
}
