package solutions.shapeit.wethrive.identity.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.ForgotPasswordRequest;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.LoginRequest;
import solutions.shapeit.wethrive.identity.dto.AuthDtos.TokenRequest;

class AuthDtosValidationTest {
    @Test
    void boundsCredentialTokenAndEmailInputsBeforeServiceWork() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();

            assertThat(validator.validate(new LoginRequest("person@example.test", "x".repeat(73),
                    null, "Browser", null, null))).isNotEmpty();
            assertThat(validator.validate(new TokenRequest("t".repeat(4097)))).isNotEmpty();
            assertThat(validator.validate(new ForgotPasswordRequest("a".repeat(310) + "@example.test"))).isNotEmpty();
            assertThat(validator.validate(new LoginRequest("person@example.test", "valid-password",
                    null, "Browser", null, null))).isEmpty();
        }
    }
}
