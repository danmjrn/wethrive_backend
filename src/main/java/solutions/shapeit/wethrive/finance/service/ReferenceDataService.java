package solutions.shapeit.wethrive.finance.service;

import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import solutions.shapeit.wethrive.common.domain.DomainChangeRecorder;
import solutions.shapeit.wethrive.common.domain.DomainEnums.SyncOperationType;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.CategoryResponse;
import solutions.shapeit.wethrive.finance.dto.FinanceDtos.IncomeTypeResponse;
import solutions.shapeit.wethrive.common.domain.ReferenceDataProvisioner;
import solutions.shapeit.wethrive.finance.entity.Category;
import solutions.shapeit.wethrive.finance.entity.IncomeType;
import solutions.shapeit.wethrive.finance.repository.CategoryRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeTypeRepository;

@Service
public class ReferenceDataService implements ReferenceDataProvisioner {
    public static final List<String> DEFAULT_CATEGORIES = List.of(
            "Bank Charges", "Childcare", "Clothing & Accessories", "Contracts", "Contribution", "Debt Payoff",
            "Dining Out & Takeaways", "Education", "Electricity", "Emergency Funds", "Entertainment",
            "Financial Aid", "Fuel", "Gifts", "Groceries", "Home", "Household Maintenance", "Insurance",
            "Internet & Mobile", "Investments", "Medical & Health", "Miscellaneous", "Mortgage / Bond",
            "Personal Development", "Pet Care", "Public Transport", "Rent", "Savings", "Self Care",
            "Stokvel Contribution", "Subscriptions", "Taxes", "Tithe & Offering", "Transportation", "Travel",
            "Utilities", "Vehicle Maintenance", "Water");
    public static final List<String> DEFAULT_INCOME_TYPES = List.of(
            "Salary", "Gift", "Side Hustle", "Interest", "Borrowed Funds", "Debt Recovery", "Stokvel Payment");

    private final CategoryRepository categories;
    private final IncomeTypeRepository incomeTypes;
    private final ObjectProvider<DomainChangeRecorder> changes;

    public ReferenceDataService(CategoryRepository categories, IncomeTypeRepository incomeTypes,
                                ObjectProvider<DomainChangeRecorder> changes) {
        this.categories = categories;
        this.incomeTypes = incomeTypes;
        this.changes = changes;
    }

    @Override
    @Transactional
    public void initializeForSpace(UUID spaceId, UUID actorUserId) {
        for (int index = 0; index < DEFAULT_CATEGORIES.size(); index++) {
            String categoryName = DEFAULT_CATEGORIES.get(index);
            // Initialization may be retried after a lost response. Any historical match, including
            // an archived or soft-deleted category, represents an intentional user record.
            if (categories.existsBySpaceIdAndNameIgnoreCase(spaceId, categoryName)) continue;
            Category category = new Category();
            category.setId(UUID.randomUUID());
            category.setSpaceId(spaceId);
            category.setCreatedByUserId(actorUserId);
            category.setUpdatedByUserId(actorUserId);
            category.setName(categoryName);
            category.setSortOrder(index + 1);
            category.setSystemDefault(true);
            category = categories.saveAndFlush(category);
            CategoryResponse response = new CategoryResponse(category.getId(), spaceId, category.getName(),
                    category.getIcon(), category.getSortOrder(), false, true, category.getVersion());
            Category saved = category;
            changes.orderedStream().forEach(recorder -> recorder.record(spaceId, "CATEGORY", saved.getId(),
                    SyncOperationType.CREATE, saved.getVersion(), actorUserId, false, response));
        }
        for (int index = 0; index < DEFAULT_INCOME_TYPES.size(); index++) {
            String typeName = DEFAULT_INCOME_TYPES.get(index);
            if (incomeTypes.existsBySpaceIdAndNameIgnoreCase(spaceId, typeName)) continue;
            IncomeType type = new IncomeType();
            type.setId(UUID.randomUUID());
            type.setSpaceId(spaceId);
            type.setCreatedByUserId(actorUserId);
            type.setUpdatedByUserId(actorUserId);
            type.setName(typeName);
            type.setSortOrder(index + 1);
            type.setSystemDefault(true);
            type = incomeTypes.saveAndFlush(type);
            IncomeTypeResponse response = new IncomeTypeResponse(type.getId(), spaceId, type.getName(),
                    type.getSortOrder(), false, true, type.getVersion());
            IncomeType saved = type;
            changes.orderedStream().forEach(recorder -> recorder.record(spaceId, "INCOME_TYPE", saved.getId(),
                    SyncOperationType.CREATE, saved.getVersion(), actorUserId, false, response));
        }
    }
}
