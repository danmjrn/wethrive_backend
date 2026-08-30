package solutions.shapeit.wethrive.identity.service;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import solutions.shapeit.wethrive.common.web.ApiException;

@Component
public class CurrentUser {
    public UserPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication is required");
        }
        return principal;
    }

    public UUID id() { return principal().id(); }
}
