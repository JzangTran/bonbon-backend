package com.bonbon.backend.common.config;

import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.method.HandlerMethod;

/**
 * The API contract the web and mobile clients generate their types from, and the reference people read at
 * {@code /scalar}. Served only where {@code springdoc.api-docs.enabled=true} (dev profile).
 *
 * <p>Every operation gets, on top of what its controller declares: the shared {@code Problem} error schema, a 400
 * when it takes input, 401 and 403 when it needs a login or a permission, a 500, and its own business errors from
 * {@link ApiError}. Public operations carry no security requirement, so the reference does not ask for a token.
 */
@Configuration
class OpenApiConfig {

    private static final String BEARER = "bearerAuth";
    private static final String PROBLEM = "Problem";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final Pattern AUTHORITY = Pattern.compile("hasAuthority\\('([^']+)'\\)");

    static {
        // Resolved from the access token by CurrentPrincipalArgumentResolver, not sent by clients.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(CurrentPrincipal.class);
    }

    @Bean
    OpenAPI bonbonOpenApi() {
        return new OpenAPI()
                .info(new Info().title("bonbon API").version("v1").description(DESCRIPTION))
                .components(new Components()
                        .addSecuritySchemes(BEARER, new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("Access token từ `POST /api/auth/login` (hoặc đăng nhập quản trị), sống 15 phút; "
                                        + "làm mới bằng `POST /api/auth/refresh`."))
                        .addSchemas(PROBLEM, problemSchema()))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    /** Adds the standard and the declared error responses to each operation, and drops the token for public ones. */
    @Bean
    OperationCustomizer errorResponses() {
        return (operation, handler) -> {
            boolean secured = requiresLogin(handler);
            String permission = permission(handler);
            Map<Integer, List<String[]>> errors = new TreeMap<>();
            if (takesInput(handler)) {
                add(errors, 400, "VALIDATION_FAILED", "Dữ liệu gửi lên không hợp lệ; `errors` liệt kê từng trường và lý do.");
            }
            if (secured) {
                add(errors, 401, "UNAUTHENTICATED", "Thiếu access token, token sai hoặc đã hết hạn.");
            }
            if (permission != null) {
                add(errors, 403, "FORBIDDEN", "Vai trò đang dùng không có quyền `" + permission + "`.");
            }
            for (ApiError e : AnnotatedElementUtils.findMergedRepeatableAnnotations(handler.getMethod(), ApiError.class)) {
                add(errors, e.status(), e.code(), e.when());
            }
            add(errors, 500, "INTERNAL_ERROR", "Lỗi không lường trước ở máy chủ.");

            ApiResponses responses = operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
            errors.forEach((status, list) -> responses.addApiResponse(String.valueOf(status), problemResponse(status, list)));
            operation.setResponses(responses);

            if (!secured) {
                operation.setSecurity(List.of());
            }
            operation.setDescription(withAccess(operation.getDescription(), secured, permission));
            return operation;
        };
    }

    /** Sidebar groups by caller (Scalar reads {@code x-tagGroups}) and tags in that order. */
    @Bean
    OpenApiCustomizer tagGroups() {
        return openApi -> {
            List<Map<String, Object>> groups = new ArrayList<>();
            ApiTags.GROUPS.forEach((name, tags) -> groups.add(Map.of("name", name, "tags", tags)));
            openApi.addExtension("x-tagGroups", groups);
            if (openApi.getTags() != null) {
                List<String> order = ApiTags.GROUPS.values().stream().flatMap(List::stream).toList();
                List<Tag> sorted = new ArrayList<>(openApi.getTags());
                sorted.sort((a, b) -> Integer.compare(indexOf(order, a.getName()), indexOf(order, b.getName())));
                openApi.setTags(sorted);
            }
        };
    }

    /**
     * Describes the fields whose meaning follows a project-wide convention, wherever they appear, unless a DTO
     * already says more: money is whole VND, instants are UTC, ids are UUIDs.
     */
    @Bean
    PropertyCustomizer conventionDescriptions() {
        return (property, type) -> {
            String name = type.getPropertyName();
            if (name == null || (property.getDescription() != null && !property.getDescription().isBlank())) {
                return property;
            }
            if (MONEY.contains(name)) {
                property.setDescription("Số tiền, số nguyên VND (không có phần thập phân).");
            } else if (name.endsWith("At") || name.endsWith("Deadline")) {
                property.setDescription("Thời điểm ISO 8601 theo UTC; hiển thị theo giờ Việt Nam.");
            } else if (name.equals("id") || name.endsWith("Id")) {
                property.setDescription("UUID.");
            }
            return property;
        };
    }

    private static final java.util.Set<String> MONEY = java.util.Set.of("price", "priceDelta", "unitPrice", "lineTotal", "itemsTotal",
            "discount", "deliveryFee", "grandTotal", "minOrderValue", "freeDeliveryThreshold", "commissionAmount", "amount");

    // --- helpers

    private static int indexOf(List<String> order, String name) {
        int i = order.indexOf(name);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    private static void add(Map<Integer, List<String[]>> errors, int status, String code, String when) {
        List<String[]> list = errors.computeIfAbsent(status, k -> new ArrayList<>());
        if (list.stream().noneMatch(e -> e[0].equals(code))) {
            list.add(new String[] {code, when});
        }
    }

    private static ApiResponse problemResponse(int status, List<String[]> errors) {
        StringBuilder description = new StringBuilder();
        Map<String, Example> examples = new LinkedHashMap<>();
        for (String[] e : errors) {
            description.append("- `").append(e[0]).append("`: ").append(e[1]).append('\n');
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("status", status);
            value.put("code", e[0]);
            value.put("detail", e[1]);
            examples.put(e[0], new Example().summary(e[0]).value(value));
        }
        MediaType media = new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM)).examples(examples);
        return new ApiResponse().description(description.toString().strip()).content(new Content().addMediaType(PROBLEM_JSON, media));
    }

    /** A login is needed when a permission is checked or the caller's identity is an argument. */
    private static boolean requiresLogin(HandlerMethod handler) {
        return permission(handler) != null
                || Arrays.stream(handler.getMethod().getParameters()).anyMatch(p -> p.getType() == CurrentPrincipal.class);
    }

    private static String permission(HandlerMethod handler) {
        PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
        if (rule == null) {
            rule = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
        }
        if (rule == null) {
            return null;
        }
        Matcher m = AUTHORITY.matcher(rule.value());
        return m.find() ? m.group(1) : rule.value();
    }

    private static boolean takesInput(HandlerMethod handler) {
        for (Parameter p : handler.getMethod().getParameters()) {
            if (p.isAnnotationPresent(Valid.class) || p.isAnnotationPresent(RequestBody.class) || p.isAnnotationPresent(RequestParam.class)
                    || p.isAnnotationPresent(PathVariable.class) || p.isAnnotationPresent(RequestPart.class)) {
                return true;
            }
        }
        return false;
    }

    private static String withAccess(String description, boolean secured, String permission) {
        String access = !secured ? "**Không cần đăng nhập.**"
                : permission != null ? "**Cần đăng nhập**, quyền `" + permission + "`." : "**Cần đăng nhập.**";
        return description == null || description.isBlank() ? access : description.strip() + "\n\n" + access;
    }

    private static Schema<?> problemSchema() {
        ObjectSchema fieldError = new ObjectSchema();
        fieldError.addProperty("field", new StringSchema().description("Tên trường, ví dụ `items[0].quantity`"));
        fieldError.addProperty("message", new StringSchema().description("Lý do, bằng tiếng Việt"));
        ObjectSchema problem = new ObjectSchema();
        problem.description("Mọi lỗi đều theo RFC 9457 (`application/problem+json`). Hãy rẽ nhánh theo `code`, không theo câu chữ của "
                + "`detail`. Một số lỗi có thêm trường riêng, ví dụ `menuItemId` (món gây lỗi) hay `status` (trạng thái hiện tại).");
        problem.addProperty("type", new StringSchema().example("about:blank"));
        problem.addProperty("title", new StringSchema().description("Tên chuẩn của mã HTTP").example("Conflict"));
        problem.addProperty("status", new IntegerSchema().description("Mã HTTP").example(409));
        problem.addProperty("detail", new StringSchema().description("Câu giải thích cho người dùng, tiếng Việt").example("Quán đang đóng cửa hoặc tạm ngưng nhận đơn."));
        problem.addProperty("instance", new StringSchema().description("Đường dẫn của yêu cầu").example("/api/orders"));
        problem.addProperty("code", new StringSchema().description("Mã lỗi ổn định để client xử lý").example("SHOP_CLOSED"));
        problem.addProperty("errors", new ArraySchema().items(fieldError).description("Chỉ có khi `code` là `VALIDATION_FAILED`"));
        problem.setAdditionalProperties(true);
        problem.setRequired(List.of("status", "code"));
        return problem;
    }

    private static final String DESCRIPTION = """
            API của bonbon: đặt món từ quán ăn trong khu (chung cư, khu công nghiệp), giao nội khu.

            **Ai gọi API nào.** Mục bên trái chia theo người gọi: *Chung* (đăng nhập, tài khoản, thông báo), *Khách* (ứng dụng \
            di động), *Người bán* (di động và web), *Quản trị* (chỉ web).

            **Đăng nhập.** Gửi `Authorization: Bearer <access token>`. Mỗi phiên chỉ có một vai trò (khách hoặc người bán); \
            quyền nằm trong token. Access token sống 15 phút, làm mới bằng refresh token.

            **Lỗi.** Mọi lỗi trả về `application/problem+json` với trường `code` cố định (xem schema `Problem`). Mỗi API liệt kê \
            các mã lỗi nó có thể trả và khi nào.

            **Quy ước.** Tiền là số nguyên VND. Thời điểm là ISO 8601 UTC; ngày (`from`, `to`) tính theo giờ Việt Nam. Id là UUID.

            **Thời gian thực.** Cập nhật đơn hàng qua WebSocket `/ws/orders`: tin nhắn đầu tiên là \
            `{"type":"auth","token":"<access token>"}`, sau đó nhận `{"type":"order","orderId",...}` và tải lại đơn.
            """;
}
