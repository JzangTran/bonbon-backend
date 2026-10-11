package com.bonbon.backend.messaging.controller;

import java.time.Instant;
import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.messaging.dto.ChatRequests;
import com.bonbon.backend.messaging.dto.ChatViews;
import com.bonbon.backend.messaging.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.format.annotation.DateTimeFormat;

@Tag(name = ApiTags.CHAT, description = "Khách nhắn tin cho cửa hàng và cửa hàng trả lời: chữ (tối đa 1.000 ký tự), một ảnh mỗi tin và trả lời một tin cụ thể. Cuộc trò chuyện được tạo ở tin nhắn đầu tiên, mỗi cặp khách và cửa hàng một cuộc. Người gọi là khách (đang dùng vai trò khách) hoặc chủ cửa hàng (vai trò người bán); mỗi bên chỉ thấy các cuộc của mình. Khách chỉ thấy \"cửa hàng\", không thấy ai bên cửa hàng đã viết. Tin mới được báo tức thì qua WebSocket `/ws/orders` (loại `message`), nhưng dữ liệu luôn được lưu và lấy lại qua các endpoint này.")
@RestController
@RequestMapping("/api")
@Validated
class ChatController {

    private final ChatService chat;

    ChatController(ChatService chat) {
        this.chat = chat;
    }

    @Operation(operationId = "listConversations", summary = "Danh sách cuộc trò chuyện", description = "Các cuộc trò chuyện của người gọi, mới nhất trước, kèm tin cuối để xem trước và dấu chưa đọc. `unreadConversations` là tổng số cuộc còn tin chưa đọc. Chưa đọc tính theo từng phía (khách, hoặc cả cửa hàng), không theo từng người.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người bán chưa có cửa hàng được duyệt.")
    @ApiError(status = 403, code = "NOT_A_PARTICIPANT", when = "Vai trò đang dùng không phải khách hoặc người bán.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–50.")
    @GetMapping("/conversations")
    @PreAuthorize("hasAuthority('conversation:read')")
    ChatViews.Page list(CurrentPrincipal principal, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return chat.list(principal, page, size);
    }

    @Operation(operationId = "getConversationWithShop", summary = "Cuộc trò chuyện của khách với một cửa hàng", description = "Để mở khung chat từ màn hình cửa hàng. Chưa nhắn lần nào thì trả 404; khi đó chỉ cần gửi tin đầu tiên.")
    @ApiError(status = 404, code = "CONVERSATION_NOT_FOUND", when = "Khách chưa nhắn tin với cửa hàng này.")
    @ApiError(status = 403, code = "NOT_A_PARTICIPANT", when = "Người gọi là cửa hàng hoặc không phải khách.")
    @GetMapping("/shops/{vendorId}/conversation")
    @PreAuthorize("hasAuthority('conversation:read')")
    ChatViews.Conversation withShop(CurrentPrincipal principal, @PathVariable UUID vendorId) {
        return chat.withShop(principal, vendorId);
    }

    @Operation(operationId = "listConversationMessages", summary = "Lịch sử tin nhắn", description = "Tin nhắn mới nhất trước. Để lấy trang cũ hơn, truyền `nextBefore` của lần trước vào `before`. Ảnh là liên kết ngắn hạn (15 phút). `mine` cho biết tin do phía của người gọi gửi.")
    @ApiError(status = 404, code = "CONVERSATION_NOT_FOUND", when = "Không có cuộc trò chuyện này ở phía của người gọi.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người bán chưa có cửa hàng được duyệt.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`size` ngoài 1–50.")
    @GetMapping("/conversations/{id}/messages")
    @PreAuthorize("hasAuthority('conversation:read')")
    ChatViews.Messages messages(CurrentPrincipal principal, @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before, @RequestParam(defaultValue = "30") int size) {
        return chat.messages(principal, id, before, size);
    }

    @Operation(operationId = "markConversationRead", summary = "Đánh dấu đã đọc", description = "Đặt mốc đọc của phía người gọi bằng thời điểm hiện tại. Gửi tin cũng tự đánh dấu đã đọc.")
    @ApiError(status = 404, code = "CONVERSATION_NOT_FOUND", when = "Không có cuộc trò chuyện này ở phía của người gọi.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người bán chưa có cửa hàng được duyệt.")
    @ApiResponse(responseCode = "204", description = "Đã đánh dấu.")
    @PostMapping("/conversations/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('conversation:read')")
    void read(CurrentPrincipal principal, @PathVariable UUID id) {
        chat.markRead(principal, id);
    }

    @Operation(operationId = "sendMessageToShop", summary = "Khách nhắn cho cửa hàng", description = "Tin đầu tiên tạo cuộc trò chuyện. Cần chữ hoặc ảnh (đã tải lên qua `conversations/images`), có thể kèm tin đang trả lời. Giới hạn 30 tin mỗi phút.")
    @ApiError(status = 404, code = "SHOP_NOT_FOUND", when = "Cửa hàng không tồn tại hoặc chưa được duyệt.")
    @ApiError(status = 403, code = "NOT_A_PARTICIPANT", when = "Người gọi là cửa hàng hoặc không phải khách.")
    @ApiError(status = 400, code = "MESSAGE_EMPTY", when = "Không có chữ lẫn ảnh.")
    @ApiError(status = 400, code = "MESSAGE_TOO_LONG", when = "Chữ quá 1.000 ký tự.")
    @ApiError(status = 400, code = "IMAGE_INVALID", when = "Khoá ảnh không phải ảnh người gọi đã tải lên.")
    @ApiError(status = 400, code = "REPLY_NOT_FOUND", when = "Tin được trả lời không thuộc cuộc trò chuyện này.")
    @ApiError(status = 429, code = "TOO_MANY_MESSAGES", when = "Quá 30 tin trong một phút.")
    @ApiResponse(responseCode = "201", description = "Đã gửi.")
    @PostMapping("/shops/{vendorId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('conversation:write')")
    ChatViews.Message sendToShop(CurrentPrincipal principal, @PathVariable UUID vendorId, @Valid @RequestBody ChatRequests.Send request) {
        return chat.sendToShop(principal, vendorId, request);
    }

    @Operation(operationId = "sendConversationMessage", summary = "Gửi tin trong cuộc trò chuyện", description = "Khách hoặc cửa hàng trả lời trong một cuộc trò chuyện đã có. Khách không gửi được khi cửa hàng không còn nhận đơn mới (chưa duyệt hoặc đã đình chỉ); cửa hàng vẫn trả lời được.")
    @ApiError(status = 404, code = "CONVERSATION_NOT_FOUND", when = "Không có cuộc trò chuyện này ở phía của người gọi.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người bán chưa có cửa hàng được duyệt.")
    @ApiError(status = 409, code = "SHOP_UNAVAILABLE", when = "Cửa hàng hiện không nhận tin nhắn của khách.")
    @ApiError(status = 400, code = "MESSAGE_EMPTY", when = "Không có chữ lẫn ảnh.")
    @ApiError(status = 400, code = "MESSAGE_TOO_LONG", when = "Chữ quá 1.000 ký tự.")
    @ApiError(status = 400, code = "IMAGE_INVALID", when = "Khoá ảnh không phải ảnh người gọi đã tải lên.")
    @ApiError(status = 400, code = "REPLY_NOT_FOUND", when = "Tin được trả lời không thuộc cuộc trò chuyện này.")
    @ApiError(status = 429, code = "TOO_MANY_MESSAGES", when = "Quá 30 tin trong một phút.")
    @ApiResponse(responseCode = "201", description = "Đã gửi.")
    @PostMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('conversation:write')")
    ChatViews.Message send(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody ChatRequests.Send request) {
        return chat.send(principal, id, request);
    }

    @Operation(operationId = "uploadChatImage", summary = "Tải ảnh cho tin nhắn", description = "Tải một ảnh (JPEG, PNG hoặc WEBP, tối đa 5 MB, kiểm tra theo nội dung tệp) trước khi gửi; ảnh lưu riêng tư và chỉ xem được qua liên kết ngắn hạn. Gửi `imageKey` trong tin nhắn. Giới hạn 30 ảnh mỗi giờ.")
    @ApiError(status = 413, code = "FILE_TOO_LARGE", when = "Ảnh vượt 5 MB.")
    @ApiError(status = 415, code = "UNSUPPORTED_FILE_TYPE", when = "Định dạng không được nhận (kiểm tra theo nội dung tệp).")
    @ApiError(status = 429, code = "TOO_MANY_UPLOADS", when = "Tải quá 30 ảnh trong một giờ.")
    @PostMapping(path = "/conversations/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('conversation:write')")
    ChatViews.Upload image(CurrentPrincipal principal, @RequestPart("file") MultipartFile file) {
        return chat.uploadImage(principal, file);
    }
}
