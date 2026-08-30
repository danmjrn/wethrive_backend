package solutions.shapeit.wethrive.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import solutions.shapeit.wethrive.common.domain.DomainChangeRecorder;
import solutions.shapeit.wethrive.finance.entity.Category;
import solutions.shapeit.wethrive.finance.entity.IncomeType;
import solutions.shapeit.wethrive.finance.repository.CategoryRepository;
import solutions.shapeit.wethrive.finance.repository.IncomeTypeRepository;

/**
 * Pins complete, ordered, and retry-safe reference provisioning for new spaces.
 *
 * @author Daniel Jr Nkulu
 */
class ReferenceDataServiceTest {
    @Test
    void newSpaceReceivesEveryCanonicalCategoryExactlyOnceAcrossRetries() {
        UUID spaceId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        CategoryRepository categories = mock(CategoryRepository.class);
        IncomeTypeRepository incomeTypes = mock(IncomeTypeRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<DomainChangeRecorder> changes = mock(ObjectProvider.class);
        when(changes.orderedStream()).thenAnswer(invocation -> Stream.empty());

        Set<String> categoryNames = new HashSet<>();
        Set<String> typeNames = new HashSet<>();
        var savedCategories = new ArrayList<Category>();
        var savedTypes = new ArrayList<IncomeType>();
        when(categories.existsBySpaceIdAndNameIgnoreCase(any(), any())).thenAnswer(invocation ->
                categoryNames.contains(invocation.getArgument(1, String.class).toLowerCase(Locale.ROOT)));
        when(categories.saveAndFlush(any(Category.class))).thenAnswer(invocation -> {
            Category value = invocation.getArgument(0);
            categoryNames.add(value.getName().toLowerCase(Locale.ROOT));
            savedCategories.add(value);
            return value;
        });
        when(incomeTypes.existsBySpaceIdAndNameIgnoreCase(any(), any())).thenAnswer(invocation ->
                typeNames.contains(invocation.getArgument(1, String.class).toLowerCase(Locale.ROOT)));
        when(incomeTypes.saveAndFlush(any(IncomeType.class))).thenAnswer(invocation -> {
            IncomeType value = invocation.getArgument(0);
            typeNames.add(value.getName().toLowerCase(Locale.ROOT));
            savedTypes.add(value);
            return value;
        });

        ReferenceDataService service = new ReferenceDataService(categories, incomeTypes, changes);
        service.initializeForSpace(spaceId, actorId);
        service.initializeForSpace(spaceId, actorId);

        assertThat(savedCategories).hasSize(ReferenceDataService.DEFAULT_CATEGORIES.size());
        assertThat(savedCategories).extracting(Category::getName)
                .containsExactlyElementsOf(ReferenceDataService.DEFAULT_CATEGORIES);
        assertThat(savedCategories).extracting(Category::getSortOrder)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(
                        1, ReferenceDataService.DEFAULT_CATEGORIES.size()).boxed().toList());
        assertThat(savedCategories).allSatisfy(category -> {
            assertThat(category.isSystemDefault()).isTrue();
            assertThat(category.getSpaceId()).isEqualTo(spaceId);
        });
        assertThat(savedTypes).hasSize(ReferenceDataService.DEFAULT_INCOME_TYPES.size());
    }
}
