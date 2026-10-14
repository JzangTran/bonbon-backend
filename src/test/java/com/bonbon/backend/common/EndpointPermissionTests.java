package com.bonbon.backend.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.bonbon.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The security filter chain only demands *a* token for most paths; which role may call an endpoint is decided by
 * {@code @PreAuthorize} on the controller. A missing annotation under /api/admin or /api/merchant would let any
 * signed-in customer in, so every such endpoint must carry one (on the method or its class).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class EndpointPermissionTests {

    /** Endpoints under the protected prefixes that are deliberately open (login and the like). */
    private static final List<String> OPEN = List.of("/api/admin/auth/");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void everyAdminAndMerchantEndpointDeclaresWhoMayCallIt() {
        List<String> unguarded = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod handler = e.getValue();
            boolean guarded = AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PreAuthorize.class)
                    || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), PreAuthorize.class);
            for (String path : e.getKey().getPatternValues()) {
                boolean protectedPath = path.startsWith("/api/admin/") || path.startsWith("/api/merchant/");
                if (!protectedPath || OPEN.stream().anyMatch(path::startsWith)) {
                    continue;
                }
                checked++;
                if (!guarded) {
                    unguarded.add(e.getKey().getMethodsCondition() + " " + path + " (" + handler.getShortLogMessage() + ")");
                }
            }
        }
        assertThat(checked).as("admin and merchant endpoints found").isGreaterThan(50);
        assertThat(unguarded).as("endpoints without @PreAuthorize").isEmpty();
    }
}
