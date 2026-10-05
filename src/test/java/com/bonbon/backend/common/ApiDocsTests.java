package com.bonbon.backend.common;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.common.openapi.ApiTags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Keeps the API reference readable and complete (backend#71): every operation has a human tag from {@link ApiTags},
 * a summary, a description, a readable unique operationId and its error responses in the shared Problem schema.
 * A new endpoint without them fails the build.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ApiDocsTests {

    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

    @Autowired
    MockMvc mvc;

    JsonNode doc;

    @BeforeEach
    void load() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        doc = JsonMapper.builder().build().readTree(body);
    }

    @Test
    void everyOperationIsNamedDescribedAndListsItsErrors() {
        Set<String> knownTags = new HashSet<>();
        ApiTags.GROUPS.values().forEach(knownTags::addAll);
        Set<String> operationIds = new HashSet<>();
        List<String> problems = new ArrayList<>();

        for (Map.Entry<String, JsonNode> path : doc.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                if (!METHODS.contains(op.getKey())) {
                    continue;
                }
                String where = op.getKey().toUpperCase() + " " + path.getKey();
                JsonNode o = op.getValue();
                String id = o.path("operationId").asString("");
                if (!id.matches("[a-z][A-Za-z0-9]+") || !operationIds.add(id)) {
                    problems.add(where + ": operationId '" + id + "' is not a unique camelCase name");
                }
                if (o.path("summary").asString("").isBlank()) {
                    problems.add(where + ": no summary");
                }
                if (o.path("description").asString("").isBlank()) {
                    problems.add(where + ": no description");
                }
                for (JsonNode tag : o.path("tags")) {
                    if (!knownTags.contains(tag.asString())) {
                        problems.add(where + ": tag '" + tag.asString() + "' is not in ApiTags.GROUPS");
                    }
                }
                boolean hasError = false;
                for (Map.Entry<String, JsonNode> response : o.path("responses").properties()) {
                    if (response.getKey().startsWith("4") || response.getKey().startsWith("5")) {
                        hasError = true;
                        if (!response.getValue().path("content").path("application/problem+json").path("schema").path("$ref").asString("")
                                .endsWith("/Problem")) {
                            problems.add(where + ": " + response.getKey() + " does not use the Problem schema");
                        }
                    }
                }
                if (!hasError) {
                    problems.add(where + ": no error responses");
                }
            }
        }
        assertThat(problems).isEmpty();
        assertThat(operationIds).hasSizeGreaterThan(80);
    }

    @Test
    void publicOperationsAskForNoTokenAndProtectedOnesDocumentTheirPermission() {
        JsonNode browse = doc.path("paths").path("/api/vendors").path("get");
        assertThat(browse.path("security").isArray()).isTrue();
        assertThat(browse.path("security")).isEmpty();
        assertThat(browse.path("responses").has("401")).isFalse();

        JsonNode place = doc.path("paths").path("/api/orders").path("post");
        assertThat(place.path("responses").has("401")).isTrue();
        assertThat(place.path("responses").has("403")).isTrue();
        assertThat(place.path("responses").has("201")).isTrue();
        assertThat(place.path("responses").has("200")).isTrue();
        assertThat(place.path("description").asString()).contains("order:create");
        assertThat(place.path("responses").path("409").path("content").path("application/problem+json").path("examples").has("SHOP_CLOSED")).isTrue();
        assertThat(place.path("tags").get(0).asString()).isEqualTo(ApiTags.CUSTOMER_ORDERS);
    }

    @Test
    void tagGroupsMatchTheSidebar() {
        JsonNode groups = doc.path("x-tagGroups");
        assertThat(groups.size()).isEqualTo(4);
        assertThat(groups.get(0).path("name").asString()).isEqualTo("Chung");
        assertThat(doc.path("components").path("schemas").has("Problem")).isTrue();
    }
}
