package solutions.shapeit.wethrive.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Verifies the stable structured error contract exposed by {@link GlobalExceptionHandler}.
 *
 * @author Daniel Jr Nkulu
 */
class GlobalExceptionHandlerTest {
    @Test
    void optimisticLockFailureMapsToStructuredConflict() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/api/v1/budgets/00000000-0000-0000-0000-000000000001");
        MDC.put("correlationId", "test-correlation-id");

        try {
            var response = new GlobalExceptionHandler().optimisticConflict(
                    new OptimisticLockingFailureException("stale write"), request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(409);
            assertThat(response.getBody().code()).isEqualTo("optimistic_lock_conflict");
            assertThat(response.getBody().message()).contains("reload").contains("try again");
            assertThat(response.getBody().path()).isEqualTo(request.getRequestURI());
            assertThat(response.getBody().correlationId()).isEqualTo("test-correlation-id");
            assertThat(response.getBody().violations()).isEmpty();
        } finally {
            MDC.remove("correlationId");
        }
    }
}
