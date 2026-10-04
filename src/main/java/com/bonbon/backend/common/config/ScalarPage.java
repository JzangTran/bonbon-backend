package com.bonbon.backend.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The API reference at {@code /scalar}, rendered by Scalar from the OpenAPI document at {@code /v3/api-docs}.
 * It exists only where the document itself is served ({@code springdoc.api-docs.enabled=true}, the dev profile).
 * The Scalar script is loaded from a CDN, so the page needs internet access; the API is not affected without it.
 */
@RestController
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
class ScalarPage {

    private static final String PAGE = """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1" />
                <title>bonbon API</title>
              </head>
              <body>
                <script id="api-reference" data-url="/v3/api-docs"></script>
                <script src="https://cdn.jsdelivr.net/npm/@scalar/api-reference"></script>
              </body>
            </html>
            """;

    @GetMapping(path = "/scalar", produces = MediaType.TEXT_HTML_VALUE)
    String page() {
        return PAGE;
    }
}
