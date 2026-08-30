package solutions.shapeit.wethrive.finance.service;

import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import solutions.shapeit.wethrive.common.domain.DomainChangeRecorder;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncOperationType;
import solutions.shapeit.wethrive.common.web.ApiException;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.CategoryRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.CategoryResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.CategoryUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeTypeRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeTypeResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeTypeUpdateRequest;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.VersionRequest;
import solutions.shapeit.wethrive.finance.entity.Category;
import solutions.shapeit.wethrive.finance.entity.IncomeType;
import solutions.shapeit.wethrive.finance.repository.CategoryRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeTypeRepository;
import solutions.shapeit.wethrive.space.service.SpaceAccessService;
import solutions.shapeit.wethrive.space.service.SpaceAccessService.Capability;

@Service
public class ReferenceFinanceService {
    private final CategoryRepository categories;
    private final IncomeTypeRepository incomeTypes;
    private final SpaceAccessService access;
    private final ObjectProvider<DomainChangeRecorder> changes;
    private final Clock clock;

    public ReferenceFinanceService(CategoryRepository categories, IncomeTypeRepository incomeTypes,
                                   SpaceAccessService access, ObjectProvider<DomainChangeRecorder> changes, Clock clock) {
        this.categories = categories;
        this.incomeTypes = incomeTypes;
        this.access = access;
        this.changes = changes;
        this.clock = clock;
    }

    @Transactional
    public List<CategoryResponse> categories(UUID spaceId, UUID actorId) {
        access.require(spaceId, actorId, Capability.VIEW);
        return categories.findAllBySpaceIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(spaceId).stream().map(this::map).toList();
    }

    @Transactional
    public CategoryResponse createCategory(UUID spaceId, UUID actorId, CategoryRequest request) {
        access.require(spaceId, actorId, Capability.EDIT_FINANCE);
        if (categories.existsById(request.id())) throw ApiException.conflict("A category with this identifier already exists");
        if (categories.existsBySpaceIdAndNameIgnoreCaseAndDeletedAtIsNull(spaceId, request.name().trim())) {
            throw ApiException.conflict("A category with this name already exists");
        }
        Category category = new Category();
        category.setId(request.id());
        category.setSpaceId(spaceId);
        category.setCreatedByUserId(actorId);
        category.setUpdatedByUserId(actorId);
        category.setName(request.name().trim());
        category.setIcon(request.icon());
        category.setSortOrder(request.sortOrder());
        category = categories.save(category);
        record(category.getSpaceId(), "CATEGORY", category.getId(), SyncOperationType.CREATE, category.getVersion(), actorId, false, map(category));
        return map(category);
    }

    @Transactional
    public CategoryResponse updateCategory(UUID id, UUID actorId, CategoryUpdateRequest request) {
        Category category = categories.findForUpdateById(id).orElseThrow(() -> ApiException.notFound("Category"));
        access.require(category.getSpaceId(), actorId, Capability.EDIT_FINANCE);
        requireVersion(category.getVersion(), request.version());
        category.setName(request.name().trim());
        category.setIcon(request.icon());
        category.setSortOrder(request.sortOrder());
        category.setArchived(request.archived());
        category.setUpdatedByUserId(actorId);
        category = categories.save(category);
        CategoryResponse response = map(category);
        record(category.getSpaceId(), "CATEGORY", id, SyncOperationType.UPDATE, category.getVersion(), actorId, false, response);
        return response;
    }

    @Transactional
    public void archiveCategory(UUID id, UUID actorId, VersionRequest request) {
        Category category = categories.findForUpdateById(id).orElseThrow(() -> ApiException.notFound("Category"));
        access.require(category.getSpaceId(), actorId, Capability.EDIT_FINANCE);
        requireVersion(category.getVersion(), request.version());
        category.setArchived(true);
        category.setUpdatedByUserId(actorId);
        category = categories.save(category);
        record(category.getSpaceId(), "CATEGORY", id, SyncOperationType.UPDATE, category.getVersion(), actorId, false, map(category));
    }

    @Transactional
    public List<IncomeTypeResponse> incomeTypes(UUID spaceId, UUID actorId) {
        access.require(spaceId, actorId, Capability.VIEW);
        return incomeTypes.findAllBySpaceIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(spaceId).stream().map(this::map).toList();
    }

    @Transactional
    public IncomeTypeResponse createIncomeType(UUID spaceId, UUID actorId, IncomeTypeRequest request) {
        access.require(spaceId, actorId, Capability.EDIT_FINANCE);
        if (incomeTypes.existsById(request.id())) throw ApiException.conflict("An income type with this identifier already exists");
        if (incomeTypes.existsBySpaceIdAndNameIgnoreCaseAndDeletedAtIsNull(spaceId, request.name().trim())) {
            throw ApiException.conflict("An income type with this name already exists");
        }
        IncomeType type = new IncomeType();
        type.setId(request.id());
        type.setSpaceId(spaceId);
        type.setCreatedByUserId(actorId);
        type.setUpdatedByUserId(actorId);
        type.setName(request.name().trim());
        type.setSortOrder(request.sortOrder());
        type = incomeTypes.save(type);
        record(spaceId, "INCOME_TYPE", type.getId(), SyncOperationType.CREATE, type.getVersion(), actorId, false, map(type));
        return map(type);
    }

    @Transactional
    public IncomeTypeResponse updateIncomeType(UUID id, UUID actorId, IncomeTypeUpdateRequest request) {
        IncomeType type = incomeTypes.findForUpdateById(id).orElseThrow(() -> ApiException.notFound("Income type"));
        access.require(type.getSpaceId(), actorId, Capability.EDIT_FINANCE);
        requireVersion(type.getVersion(), request.version());
        type.setName(request.name().trim());
        type.setSortOrder(request.sortOrder());
        type.setArchived(request.archived());
        type.setUpdatedByUserId(actorId);
        type = incomeTypes.save(type);
        IncomeTypeResponse response = map(type);
        record(type.getSpaceId(), "INCOME_TYPE", id, SyncOperationType.UPDATE, type.getVersion(), actorId, false, response);
        return response;
    }

    @Transactional
    public void archiveIncomeType(UUID id, UUID actorId, VersionRequest request) {
        IncomeType type = incomeTypes.findForUpdateById(id).orElseThrow(() -> ApiException.notFound("Income type"));
        access.require(type.getSpaceId(), actorId, Capability.EDIT_FINANCE);
        requireVersion(type.getVersion(), request.version());
        type.setArchived(true);
        type.setUpdatedByUserId(actorId);
        type = incomeTypes.save(type);
        record(type.getSpaceId(), "INCOME_TYPE", id, SyncOperationType.UPDATE, type.getVersion(), actorId, false, map(type));
    }

    public Category requireUsableCategory(UUID id, UUID spaceId) {
        return categories.findByIdAndSpaceIdAndDeletedAtIsNull(id, spaceId).filter(c -> !c.isArchived())
                .orElseThrow(() -> ApiException.badRequest("Select an active category in this space"));
    }

    /** Locks category state while a new or updated budget item takes its reference. */
    public Category requireUsableCategoryForReference(UUID id, UUID spaceId) {
        return categories.findUsableForReference(id, spaceId).filter(category -> !category.isArchived())
                .orElseThrow(() -> ApiException.badRequest("Select an active category in this space"));
    }

    public IncomeType requireUsableIncomeType(UUID id, UUID spaceId) {
        return incomeTypes.findByIdAndSpaceIdAndDeletedAtIsNull(id, spaceId).filter(t -> !t.isArchived())
                .orElseThrow(() -> ApiException.badRequest("Select an active income type in this space"));
    }

    private CategoryResponse map(Category c) { return new CategoryResponse(c.getId(), c.getSpaceId(), c.getName(), c.getIcon(), c.getSortOrder(), c.isArchived(), c.isSystemDefault(), c.getVersion()); }
    private IncomeTypeResponse map(IncomeType t) { return new IncomeTypeResponse(t.getId(), t.getSpaceId(), t.getName(), t.getSortOrder(), t.isArchived(), t.isSystemDefault(), t.getVersion()); }
    private void requireVersion(long actual, Long requested) {
        if (requested == null) throw ApiException.badRequest("version is required");
        if (actual != requested.longValue()) throw ApiException.conflict("The record was changed on another device");
    }
    private void record(UUID spaceId, String type, UUID id, SyncOperationType operation, long version, UUID actor, boolean tombstone, Object payload) {
        changes.orderedStream().forEach(recorder -> recorder.record(spaceId, type, id, operation, version, actor, tombstone, payload));
    }
}
