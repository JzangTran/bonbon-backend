package com.bonbon.backend.support.controller;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.web.ClientContext;
import com.bonbon.backend.support.dto.ContactRequests;
import com.bonbon.backend.support.dto.ContactViews;
import com.bonbon.backend.support.service.ContactRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.CONTACT, description = "Biểu mẫu liên hệ công khai trên trang trợ giúp: ai cũng gửi được, không cần đăng nhập. Đội hỗ trợ trả lời qua email người gửi điền. Biểu mẫu luôn cần reCAPTCHA; mỗi địa chỉ IP gửi tối đa 5 yêu cầu mỗi giờ và mỗi email 3 yêu cầu mỗi ngày.")
@RestController
@RequestMapping("/api/public/contact-requests")
@Validated
class ContactRequestController {

    private final ContactRequestService contacts;

    ContactRequestController(ContactRequestService contacts) {
        this.contacts = contacts;
    }

    @Operation(operationId = "submitContactRequest", summary = "Gửi yêu cầu liên hệ", description = "Không cần đăng nhập. Câu trả lời được gửi tới `email`.")
    @ApiError(status = 400, code = "CAPTCHA_REQUIRED", when = "Thiếu hoặc sai `captchaToken`.")
    @ApiError(status = 429, code = "TOO_MANY_CONTACT_REQUESTS", when = "Quá 5 yêu cầu mỗi giờ từ một địa chỉ IP, hoặc quá 3 yêu cầu mỗi ngày từ một email.")
    @ApiResponse(responseCode = "201", description = "Đã nhận.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ContactViews.Receipt submit(@Valid @RequestBody ContactRequests.Submit request, HttpServletRequest http) {
        return contacts.submit(request, ClientContext.from(http).ip());
    }
}
