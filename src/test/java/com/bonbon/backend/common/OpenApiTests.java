package com.bonbon.backend.common;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.bonbon.backend.TestcontainersConfiguration;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OpenApiTests {

    @Autowired
    MockMvc mvc;

    @Test
    void apiDocsArePublicAndDeclareBearerAuth() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("bonbon API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/account/me'].get.parameters").doesNotExist());
    }

    /**
     * springdoc names a schema after the class's simple name, so two public records both called Update would
     * collapse into one schema and the generated client types would be wrong. Nested records get explicit
     * names with @Schema(name = ...).
     */
    @Test
    void publicRecordsHaveUniqueSchemaNames() {
        List<JavaClass> records = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bonbon.backend").stream()
                .filter(c -> c.reflect().isRecord() && java.lang.reflect.Modifier.isPublic(c.reflect().getModifiers()))
                .toList();
        Map<String, List<String>> byName = records.stream().collect(Collectors.groupingBy(
                c -> {
                    Schema schema = c.reflect().getAnnotation(Schema.class);
                    return schema != null && !schema.name().isEmpty() ? schema.name() : c.getSimpleName();
                },
                Collectors.mapping(JavaClass::getName, Collectors.toList())));
        assertThat(byName).allSatisfy((name, classes) -> assertThat(classes).as(name).hasSize(1));
    }

    @Test
    void sameNamedRequestsKeepTheirOwnSchemas() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/admin/categories/{id}'].patch.requestBody.content['application/json'].schema['$ref']")
                        .value("#/components/schemas/CategoryUpdateRequest"))
                .andExpect(jsonPath("$.paths['/api/account/addresses/{id}'].patch.requestBody.content['application/json'].schema['$ref']")
                        .value("#/components/schemas/AddressUpdateRequest"))
                .andExpect(jsonPath("$.components.schemas.CategoryUpdateRequest.properties.clearCommissionRate").exists())
                .andExpect(jsonPath("$.components.schemas.AddressUpdateRequest.properties.recipientPhone").exists())
                .andExpect(jsonPath("$.components.schemas.ShopProfileUpdateRequest.properties.clearMinOrderValue").exists());
    }
}
