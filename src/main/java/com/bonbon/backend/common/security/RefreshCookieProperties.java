package com.bonbon.backend.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The cookie that carries the web app's refresh token, so no script on the page can read it. {@code secure} is off only in local
 * development (plain http); {@code domain} lets every bonbon subdomain share one sign-in (e.g. {@code bonbon.com}), empty means the
 * API's own host.
 */
@ConfigurationProperties("bonbon.security.refresh-cookie")
public record RefreshCookieProperties(
        @DefaultValue("bonbon_rt") String name,
        @DefaultValue("true") boolean secure,
        @DefaultValue("") String domain,
        @DefaultValue("Lax") String sameSite) {
}
