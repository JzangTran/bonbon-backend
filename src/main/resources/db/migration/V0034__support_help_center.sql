-- Help centre articles (flows/support/browse-help-center.md, manage-help-center-content.md). Keywords are a short list the
-- administrator curates; they are searched together with the title and the body. An article is deleted for real: nothing refers to it.
CREATE TABLE help_articles (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    title        TEXT        NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
    body         TEXT        NOT NULL CHECK (char_length(body) BETWEEN 1 AND 10000),
    audience     TEXT        NOT NULL CHECK (audience IN ('ALL', 'CUSTOMER', 'SELLER')),
    status       TEXT        NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED')),
    created_by   UUID        REFERENCES users (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX help_articles_status_idx ON help_articles (status, audience);

CREATE TABLE help_article_keywords (
    article_id UUID NOT NULL REFERENCES help_articles (id) ON DELETE CASCADE,
    keyword    TEXT NOT NULL CHECK (char_length(keyword) BETWEEN 1 AND 50),
    PRIMARY KEY (article_id, keyword)
);

-- A few starting articles so the screen is not empty; administrators edit or replace them.
INSERT INTO help_articles (title, body, audience, status) VALUES
    ('Theo dõi đơn hàng của tôi', E'Mở mục Đơn hàng và chọn đơn bạn vừa đặt. Trạng thái đơn (quán đã nhận, đang chuẩn bị, đang giao, đã giao) tự cập nhật khi quán thao tác, và bạn cũng nhận thông báo trên điện thoại nếu đã bật thông báo.\n\nNếu đơn đã được giao mà bạn chưa nhận được món, hãy xem bài "Đơn của tôi có vấn đề".', 'CUSTOMER', 'PUBLISHED'),
    ('Đơn của tôi có vấn đề: chưa nhận, thiếu món, sai món hoặc món không đảm bảo', E'Trong vòng 24 giờ kể từ khi đơn được giao, mở đơn và chọn Báo vấn đề. Chọn món bị ảnh hưởng, thêm ảnh (cần ít nhất một ảnh với sai món và chất lượng) và gửi. Bạn sẽ thấy số tiền được hoàn trước khi gửi.\n\nQuán có 12 giờ để trả lời. Nếu quán đồng ý, bạn được hoàn tiền ngay; nếu quán phản đối hoặc không trả lời kịp, quản trị viên của Bonbon sẽ xem và quyết định.', 'CUSTOMER', 'PUBLISHED'),
    ('Nhắn tin cho quán', E'Mở trang của quán và chọn Nhắn tin để hỏi về món, thời gian hoặc địa chỉ giao. Bạn có thể gửi chữ, một ảnh mỗi tin và trả lời một tin cụ thể. Nếu bạn không mở ứng dụng, bạn sẽ nhận thông báo khi quán trả lời; có thể tắt thông báo này trong Cài đặt thông báo.', 'CUSTOMER', 'PUBLISHED'),
    ('Mở cửa hàng trên Bonbon', E'Đăng ký tài khoản người bán, rồi hoàn thành các bước thông tin cửa hàng, giờ mở cửa và phạm vi giao, giấy tờ, và tài khoản nhận tiền. Sau khi gửi, quản trị viên sẽ duyệt hồ sơ; bạn nhận thông báo khi có kết quả. Cửa hàng chỉ hiển thị cho khách sau khi được duyệt.', 'SELLER', 'PUBLISHED'),
    ('Khi khách báo vấn đề với đơn đã giao', E'Bạn nhận thông báo và có 12 giờ để trả lời trong mục Khiếu nại. Chấp nhận thì khách được hoàn tiền ngay và khoản đó tính vào sổ của quán; phản đối thì kèm lý do để quản trị viên quyết định. Không trả lời kịp thì vụ việc cũng chuyển cho quản trị viên. Trong lúc chờ, phần tiền liên quan được giữ lại khỏi số có thể chi trả.', 'SELLER', 'PUBLISHED'),
    ('Liên hệ hỗ trợ Bonbon', E'Nếu bài viết ở đây chưa trả lời được câu hỏi của bạn, hãy gửi phiếu hỗ trợ trong ứng dụng (mục Trợ giúp, Liên hệ hỗ trợ). Vấn đề về một đơn đã giao hãy báo ngay trên đơn đó để được xử lý nhanh hơn.', 'ALL', 'PUBLISHED');

INSERT INTO help_article_keywords (article_id, keyword)
SELECT a.id, k.keyword FROM help_articles a
JOIN (VALUES
    ('Theo dõi đơn hàng của tôi', 'theo doi don'), ('Theo dõi đơn hàng của tôi', 'trang thai don'), ('Theo dõi đơn hàng của tôi', 'don hang'),
    ('Đơn của tôi có vấn đề: chưa nhận, thiếu món, sai món hoặc món không đảm bảo', 'hoan tien'), ('Đơn của tôi có vấn đề: chưa nhận, thiếu món, sai món hoặc món không đảm bảo', 'khieu nai'),
    ('Đơn của tôi có vấn đề: chưa nhận, thiếu món, sai món hoặc món không đảm bảo', 'chua nhan duoc hang'), ('Đơn của tôi có vấn đề: chưa nhận, thiếu món, sai món hoặc món không đảm bảo', 'thieu mon'),
    ('Nhắn tin cho quán', 'nhan tin'), ('Nhắn tin cho quán', 'chat'),
    ('Mở cửa hàng trên Bonbon', 'mo quan'), ('Mở cửa hàng trên Bonbon', 'dang ky ban hang'), ('Mở cửa hàng trên Bonbon', 'duyet'),
    ('Khi khách báo vấn đề với đơn đã giao', 'khieu nai'), ('Khi khách báo vấn đề với đơn đã giao', 'hoan tien'), ('Khi khách báo vấn đề với đơn đã giao', 'phan doi'),
    ('Liên hệ hỗ trợ Bonbon', 'ho tro'), ('Liên hệ hỗ trợ Bonbon', 'lien he')
) AS k(title, keyword) ON k.title = a.title;
