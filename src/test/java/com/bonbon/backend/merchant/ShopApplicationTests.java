package com.bonbon.backend.merchant;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.TokenService;
import com.bonbon.backend.common.geo.Geocoder;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs with the fake geocoder and S3Mock (test profile). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ShopApplicationTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @Autowired
    Geocoder geocoder;

    @Autowired
    JdbcClient jdbc;

    String seller;
    UUID sellerId;
    String placeId;

    @BeforeEach
    void setUp() {
        User u = new User("shop-" + System.nanoTime() + "@example.com", null, "Chủ quán");
        u.addRole(Role.SELLER);
        u.markEmailVerified();
        u = users.saveAndFlush(u);
        sellerId = u.getId();
        seller = tokens.issue(u, Role.SELLER, Instant.now()).accessToken();
        placeId = geocoder.autocomplete("Toà S2 Vinhomes", null, null).get(0).placeId();
    }

    @Test
    void beforeAnythingIsSavedTheStatusIsNone() throws Exception {
        mvc.perform(auth(get("/api/merchant/shop"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NONE"))
                .andExpect(jsonPath("$.firstIncompleteStep").value(1))
                .andExpect(jsonPath("$.maxDeliveryRadiusKm").value(3));
    }

    @Test
    void customersCannotOpenShops() throws Exception {
        User c = new User("cust-" + System.nanoTime() + "@example.com", null, "Khách");
        c.addRole(Role.CUSTOMER);
        c.markEmailVerified();
        String customer = tokens.issue(users.saveAndFlush(c), Role.CUSTOMER, Instant.now()).accessToken();
        mvc.perform(get("/api/merchant/shop").header("Authorization", "Bearer " + customer)).andExpect(status().isForbidden());
    }

    @Test
    void stepOneResolvesThePickedAddressOnce() throws Exception {
        step(1, Map.of("name", "Cơm tấm Cô Ba", "phone", "0912345678", "email", "coba@example.com",
                "placeId", placeId, "addressDetail", "Kiot 3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.shop.address.formattedAddress").isNotEmpty())
                .andExpect(jsonPath("$.shop.address.lat").isNumber())
                .andExpect(jsonPath("$.steps[0].complete").value(true))
                .andExpect(jsonPath("$.firstIncompleteStep").value(2));

        step(1, Map.of("placeId", "fake:nope")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ADDRESS_NOT_FOUND"));
        step(1, Map.of("phone", "12345")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void radiusMustFitUnderThePlatformCap() throws Exception {
        step(2, Map.of("openingHours", List.of(window(1, "06:00", "10:00")), "deliveryRadiusKm", 5, "deliveryFee", 10000))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELIVERY_RADIUS_TOO_LARGE"))
                .andExpect(jsonPath("$.maxDeliveryRadiusKm").value(3));
    }

    @Test
    void openingHoursMayCrossMidnightButNeverOverlap() throws Exception {
        step(2, Map.of("openingHours", List.of(window(1, "06:00", "10:00"), window(1, "18:00", "02:00"), window(2, "06:00", "10:00")),
                "deliveryRadiusKm", 2, "deliveryFee", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipping.openingHours.length()").value(3))
                .andExpect(jsonPath("$.steps[1].complete").value(true));

        step(2, Map.of("openingHours", List.of(window(1, "18:00", "02:00"), window(2, "01:00", "05:00"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OPENING_HOURS_OVERLAP"));
        // Sunday night runs into Monday morning.
        step(2, Map.of("openingHours", List.of(window(7, "22:00", "03:00"), window(1, "02:00", "06:00"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OPENING_HOURS_OVERLAP"));
        step(2, Map.of("openingHours", List.of(window(3, "08:00", "08:00"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OPENING_HOURS_INVALID"));
    }

    @Test
    void bankAccountIsEncryptedAndShownMasked() throws Exception {
        step(3, Map.of("businessType", "INDIVIDUAL", "businessAddress", "12 Ngõ 34, Hà Nội", "taxCode", "012345678901",
                "invoiceEmails", List.of("hoadon@example.com"), "bankName", "Vietcombank", "accountNumber", "0011001234567",
                "accountHolderName", "NGUYEN THI BA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tax.payout.accountLast4").value("4567"))
                .andExpect(jsonPath("$.steps[2].complete").value(true));

        String stored = jdbc.sql("""
                select p.account_number_encrypted from vendor_payout_accounts p join vendors v on v.id = p.vendor_id
                where v.owner_user_id = :owner""").param("owner", sellerId).query(String.class).single();
        assertThat(stored).startsWith("v1:").doesNotContain("0011001234567");

        // Re-saving without the number keeps it.
        step(3, Map.of("businessType", "INDIVIDUAL", "businessAddress", "12 Ngõ 34, Hà Nội", "taxCode", "012345678901",
                "invoiceEmails", List.of("hoadon@example.com"), "bankName", "Vietcombank", "accountHolderName", "Nguyễn Thị Ba"))
                .andExpect(jsonPath("$.tax.payout.accountLast4").value("4567"));

        step(3, Map.of("invoiceEmails", List.of("a@x.vn", "b@x.vn", "c@x.vn", "d@x.vn", "e@x.vn", "f@x.vn")))
                .andExpect(status().isBadRequest());
        step(3, Map.of("taxCode", "12-34")).andExpect(status().isBadRequest());
    }

    @Test
    void householdBusinessNeedsItsNameAndLicence() throws Exception {
        step(3, Map.of("businessType", "HOUSEHOLD", "businessAddress", "12 Ngõ 34", "taxCode", "0123456789",
                "invoiceEmails", List.of("hd@example.com"), "bankName", "ACB", "accountNumber", "123456789",
                "accountHolderName", "TRAN VAN B"))
                .andExpect(jsonPath("$.steps[2].complete").value(false))
                .andExpect(jsonPath("$.steps[2].missing").value(org.hamcrest.Matchers.containsInAnyOrder("businessName", "businessLicense")));
    }

    @Test
    void identityConsentsAreRecordedSeparatelyAgainstTheShownVersions() throws Exception {
        String terms = docId("SELLER_TERMS");
        String privacy = docId("PRIVACY_POLICY");
        step(4, Map.of("docType", "CCCD", "docNumber", "001200012345", "fullName", "Nguyễn Thị Ba", "accuracyConfirmed", true,
                "sellerTermsDocumentId", terms))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity.docNumberLast4").value("2345"))
                .andExpect(jsonPath("$.identity.sellerTermsAccepted").value(true))
                .andExpect(jsonPath("$.identity.identityConsentGiven").value(false));
        step(4, Map.of("docType", "CCCD", "fullName", "Nguyễn Thị Ba", "privacyPolicyDocumentId", privacy))
                .andExpect(jsonPath("$.identity.identityConsentGiven").value(true))
                .andExpect(jsonPath("$.identity.sellerTermsAccepted").value(true));

        List<String> purposes = jdbc.sql("select purpose from consent_records where principal_id = :id order by at")
                .param("id", sellerId).query(String.class).list();
        assertThat(purposes).containsExactly("TERMS", "IDENTITY_VERIFICATION");

        step(4, Map.of("privacyPolicyDocumentId", UUID.randomUUID().toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LEGAL_DOCUMENTS_CHANGED"));
        step(4, Map.of("docType", "CCCD", "docNumber", "123456789"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DOC_NUMBER_INVALID"));
    }

    @Test
    void identityPhotosArePrivateAndReplacedFilesAreRemoved() throws Exception {
        String first = JsonPath.read(upload("identity-front", PNG).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.identity.frontPhotoUrl");
        assertThat(first).contains("X-Amz-Signature=");
        assertThat(fetch(first)).isEqualTo(200);
        String firstKey = jdbc.sql("select i.front_file_key from vendor_identity i join vendors v on v.id = i.vendor_id where v.owner_user_id = :o")
                .param("o", sellerId).query(String.class).single();
        assertThat(firstKey).startsWith("vendor-identity/");

        upload("identity-front", PNG).andExpect(status().isOk());
        assertThat(fetch(first)).isEqualTo(404);

        upload("identity-selfie", "not an image".getBytes()).andExpect(status().isUnsupportedMediaType());
        upload("passport", PNG).andExpect(status().isNotFound());
    }

    @Test
    void submitNeedsEveryStepThenLocksTheApplication() throws Exception {
        step(1, Map.of("name", "Bún chả", "phone", "0912345678", "email", "bun@example.com", "placeId", placeId));
        mvc.perform(auth(post("/api/merchant/shop/submit"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHOP_APPLICATION_INCOMPLETE"))
                .andExpect(jsonPath("$.firstIncompleteStep").value(2))
                .andExpect(jsonPath("$.missing.2").isArray());

        completeSteps2to4();
        mvc.perform(auth(post("/api/merchant/shop/submit"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.submittedAt").isNotEmpty())
                .andExpect(jsonPath("$.tax.payout.holderMatchesIdentity").value(true));

        step(1, Map.of("name", "Đổi tên")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHOP_NOT_EDITABLE"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(auth(post("/api/merchant/shop/submit"))).andExpect(status().isConflict());
    }

    // --- helpers

    private void completeSteps2to4() throws Exception {
        step(2, Map.of("openingHours", List.of(window(1, "06:00", "21:00")), "deliveryRadiusKm", 2, "deliveryFee", 10000))
                .andExpect(status().isOk());
        step(3, Map.of("businessType", "INDIVIDUAL", "businessAddress", "12 Ngõ 34", "taxCode", "012345678901",
                "invoiceEmails", List.of("hd@example.com"), "bankName", "Vietcombank", "accountNumber", "0011001234567",
                "accountHolderName", "NGUYEN THI BA")).andExpect(status().isOk());
        step(4, Map.of("docType", "CCCD", "docNumber", "001200012345", "fullName", "Nguyễn Thị Ba", "accuracyConfirmed", true,
                "sellerTermsDocumentId", docId("SELLER_TERMS"), "privacyPolicyDocumentId", docId("PRIVACY_POLICY")))
                .andExpect(status().isOk());
        upload("identity-front", PNG).andExpect(status().isOk());
        upload("identity-selfie", PNG).andExpect(status().isOk());
    }

    private ResultActions step(int n, Map<String, Object> body) throws Exception {
        return mvc.perform(auth(put("/api/merchant/shop/steps/" + n)).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    private ResultActions upload(String kind, byte[] bytes) throws Exception {
        return mvc.perform(multipart("/api/merchant/shop/files/" + kind)
                .file(new MockMultipartFile("file", "photo.png", "image/png", bytes))
                .header("Authorization", "Bearer " + seller));
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + seller);
    }

    private String docId(String type) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/legal/documents/" + type)).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static Map<String, Object> window(int weekday, String opens, String closes) {
        return Map.of("weekday", weekday, "opensAt", opens, "closesAt", closes);
    }

    private static int fetch(String url) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
