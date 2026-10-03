package com.bonbon.backend.merchantapproval;

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
import com.bonbon.backend.common.mail.EmailSender;
import com.bonbon.backend.common.mail.EmailSender.EmailMessage;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ShopReviewTests {

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

    @MockitoBean
    EmailSender emails;

    String admin;
    String seller;
    String sellerEmail;
    String shopName;

    @BeforeEach
    void setUp() {
        admin = token(Role.ADMIN, "admin");
        sellerEmail = "review-" + System.nanoTime() + "@example.com";
        seller = token(Role.SELLER, sellerEmail);
        shopName = "Quán " + (System.nanoTime() % 100_000);
    }

    @Test
    void queueListsSubmittedShopsOnlyForReviewers() throws Exception {
        String vendorId = submitShop("NGUYEN THI BA");
        String drafter = token(Role.SELLER, "draft-" + System.nanoTime() + "@example.com");
        mvc.perform(auth(put("/api/merchant/shop/steps/1"), drafter).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Bản nháp " + shopName + "\"}")).andExpect(status().isOk());

        String queue = mvc.perform(auth(get("/api/admin/merchant-approval/requests").param("q", shopName), admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(queue, "$[*].shop.vendorId")).containsExactly(vendorId);
        assertThat((Boolean) JsonPath.read(queue, "$[0].resubmitted")).isFalse();

        mvc.perform(auth(get("/api/admin/merchant-approval/requests"), seller)).andExpect(status().isForbidden());
    }

    @Test
    void applicationShowsMaskedPayoutAndFlagsANameMismatch() throws Exception {
        String vendorId = submitShop("TRAN VAN KHAC");
        mvc.perform(auth(get("/api/admin/merchant-approval/requests/" + vendorId), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shop.status").value("PENDING"))
                .andExpect(jsonPath("$.shop.accountLast4").value("4567"))
                .andExpect(jsonPath("$.shop.payoutHolderMatchesIdentity").value(false))
                .andExpect(jsonPath("$.shop.identityFullName").value("Nguyễn Thị Ba"))
                .andExpect(jsonPath("$.shop.openingHours[0].weekday").value(1))
                .andExpect(jsonPath("$.shop.docNumber").doesNotExist())
                .andExpect(jsonPath("$.history.length()").value(0));
    }

    @Test
    void identityDocumentsNeedTheirOwnPermissionAndEveryViewIsLogged() throws Exception {
        String vendorId = submitShop("NGUYEN THI BA");
        mvc.perform(auth(get("/api/admin/merchant-approval/requests/" + vendorId + "/identity"), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docNumber").value("001200012345"))
                .andExpect(jsonPath("$.frontPhotoUrl").value(org.hamcrest.Matchers.containsString("X-Amz-Expires=300")));
        mvc.perform(auth(get("/api/admin/merchant-approval/requests/" + vendorId + "/identity"), admin)).andExpect(status().isOk());
        assertThat(jdbc.sql("select count(*) from identity_document_access_log where vendor_id = :v")
                .param("v", UUID.fromString(vendorId)).query(Long.class).single()).isEqualTo(2);

        mvc.perform(auth(get("/api/admin/merchant-approval/requests/" + vendorId + "/identity"), seller))
                .andExpect(status().isForbidden());
    }

    @Test
    void approvalOpensTheShopAndEmailsTheSeller() throws Exception {
        String vendorId = submitShop("NGUYEN THI BA");
        mvc.perform(auth(post("/api/admin/merchant-approval/requests/" + vendorId + "/approve"), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(auth(get("/api/merchant/shop"), seller)).andExpect(jsonPath("$.status").value("APPROVED"));
        EmailMessage mail = sentTo(sellerEmail);
        assertThat(mail.subject()).contains(shopName).contains("đã được duyệt");

        mvc.perform(auth(post("/api/admin/merchant-approval/requests/" + vendorId + "/approve"), admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHOP_NOT_PENDING"));
    }

    @Test
    void rejectionNeedsAReasonAndTheSellerCanFixAndResubmit() throws Exception {
        String vendorId = submitShop("NGUYEN THI BA");
        send(post("/api/admin/merchant-approval/requests/" + vendorId + "/reject"), admin, Map.of("reason", " "))
                .andExpect(status().isBadRequest());
        String reason = "Ảnh mặt trước CCCD bị mờ, vui lòng chụp lại.";
        send(post("/api/admin/merchant-approval/requests/" + vendorId + "/reject"), admin, Map.of("reason", reason))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(sentTo(sellerEmail).textBody()).contains(reason);

        mvc.perform(auth(get("/api/merchant/shop"), seller))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value(reason));
        upload("identity-front");
        mvc.perform(auth(post("/api/merchant/shop/submit"), seller)).andExpect(jsonPath("$.status").value("PENDING"));

        mvc.perform(auth(get("/api/admin/merchant-approval/requests").param("q", shopName), admin))
                .andExpect(jsonPath("$[0].resubmitted").value(true));
        mvc.perform(auth(get("/api/admin/merchant-approval/requests/" + vendorId), admin))
                .andExpect(jsonPath("$.history[0].decision").value("REJECTED"))
                .andExpect(jsonPath("$.history[0].reason").value(reason));
    }

    @Test
    void radiusCapIsReadableAndChangeableByAdmins() throws Exception {
        mvc.perform(auth(get("/api/admin/settings/delivery-radius-cap"), admin)).andExpect(jsonPath("$.maxRadiusKm").value(3));
        try {
            send(put("/api/admin/settings/delivery-radius-cap"), admin, Map.of("maxRadiusKm", 5)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.maxRadiusKm").value(5));
            send(put("/api/merchant/shop/steps/2"), seller, Map.of("deliveryRadiusKm", 4.5)).andExpect(status().isOk());
            send(put("/api/admin/settings/delivery-radius-cap"), admin, Map.of("maxRadiusKm", 0)).andExpect(status().isBadRequest());
            send(put("/api/admin/settings/delivery-radius-cap"), seller, Map.of("maxRadiusKm", 9)).andExpect(status().isForbidden());
        } finally {
            send(put("/api/admin/settings/delivery-radius-cap"), admin, Map.of("maxRadiusKm", 3)).andExpect(status().isOk());
        }
    }

    // --- helpers

    /** Fills every wizard step as the seller and submits; returns the vendor id. */
    private String submitShop(String accountHolder) throws Exception {
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        send(put("/api/merchant/shop/steps/1"), seller, Map.of("name", shopName, "phone", "0912345678",
                "email", "shop@example.com", "placeId", place)).andExpect(status().isOk());
        send(put("/api/merchant/shop/steps/2"), seller, Map.of("openingHours", List.of(Map.of("weekday", 1, "opensAt", "06:00",
                "closesAt", "21:00")), "deliveryRadiusKm", 2, "deliveryFee", 10000)).andExpect(status().isOk());
        send(put("/api/merchant/shop/steps/3"), seller, Map.of("businessType", "INDIVIDUAL", "businessAddress", "12 Ngõ 34",
                "taxCode", "012345678901", "invoiceEmails", List.of("hd@example.com"), "bankName", "Vietcombank",
                "accountNumber", "0011001234567", "accountHolderName", accountHolder)).andExpect(status().isOk());
        send(put("/api/merchant/shop/steps/4"), seller, Map.of("docType", "CCCD", "docNumber", "001200012345",
                "fullName", "Nguyễn Thị Ba", "accuracyConfirmed", true, "sellerTermsDocumentId", docId("SELLER_TERMS"),
                "privacyPolicyDocumentId", docId("PRIVACY_POLICY"))).andExpect(status().isOk());
        upload("identity-front");
        upload("identity-selfie");
        String json = mvc.perform(auth(post("/api/merchant/shop/submit"), seller)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("PENDING");
        return jdbc.sql("select v.id from vendors v join users u on u.id = v.owner_user_id where u.email = :e")
                .param("e", sellerEmail).query(UUID.class).single().toString();
    }

    private void upload(String kind) throws Exception {
        mvc.perform(multipart("/api/merchant/shop/files/" + kind).file(new MockMultipartFile("file", "a.png", "image/png", PNG))
                .header("Authorization", "Bearer " + seller)).andExpect(status().isOk());
    }

    private EmailMessage sentTo(String to) {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emails, timeout(5000).atLeastOnce()).send(captor.capture());
        return captor.getAllValues().stream().filter(m -> m.to().equals(to)).reduce((a, b) -> b).orElseThrow();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token, Map<String, Object> body) throws Exception {
        return mvc.perform(auth(request, token).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String docId(String type) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/legal/documents/" + type)).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String token(Role role, String email) {
        User u = new User(email.contains("@") ? email : email + "-" + System.nanoTime() + "@example.com", null, "Người dùng");
        u.addRole(role);
        u.markEmailVerified();
        return tokens.issue(users.saveAndFlush(u), role, Instant.now()).accessToken();
    }
}
