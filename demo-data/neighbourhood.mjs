// The bigger demo neighbourhood (owner 2026-11-19: "too little data"): 14 more shops across every leaf category, 12 more
// customers, and the history a busy fortnight leaves (seed.mjs runs it as the "busy" wave). Everything is invented: people,
// shops, phone numbers (09000001xx, 09000002xx) and emails on example.com.
//
// Item rows: [name, description, price, option group key or null, 'soldOut'?]

const s = (n, sellerName, shopName, place, detail, extra) => ({
  seller: { email: `demo.seller${n}@example.com`, name: sellerName },
  name: shopName, place, detail, phone: `09000001${String(n).padStart(2, '0')}`,
  ...extra,
})

export const moreShops = (AREA) => [
  s(7, 'Hà Văn Toàn', 'Bún bò Huế O Thoa', `Toà S1.01 ${AREA}`, 'Kiot 11, sảnh toà S1.01', {
    radiusKm: 3, fee: 8000, freeFrom: 150000, hours: [['06:00', '10:30'], ['16:30', '21:00']],
    menu: [
      { section: 'Bún bò', category: 'Bún & phở', items: [
        ['Bún bò Huế đặc biệt', 'Bắp bò, giò heo, chả cua, huyết.', 60000, 'size'],
        ['Bún bò thường', 'Bắp bò, chả cua.', 45000, 'size'],
        ['Bún bò giò heo', 'Giò heo hầm mềm.', 50000, 'size'],
        ['Bún bò chả cua', 'Thêm chả cua viên.', 50000],
      ] },
      { section: 'Món thêm', category: 'Bún & phở', items: [
        ['Chả cua thêm', '3 viên.', 15000],
        ['Giò heo thêm', 'Một khoanh.', 20000],
        ['Trà đá', '', 5000],
      ] },
    ],
  }),
  s(8, 'Lý Thị Hồng', 'Gà rán Giòn Tan', `Toà S2.03 ${AREA}`, 'Shophouse SH-12', {
    radiusKm: 3, fee: 10000, freeFrom: 200000, hours: [['10:00', '22:00']],
    menu: [
      { section: 'Gà rán', category: 'Đồ ăn vặt', items: [
        ['Gà rán giòn (2 miếng)', 'Đùi và cánh, tẩm bột giòn.', 59000, 'extra'],
        ['Gà rán sốt cay Hàn', 'Sốt gochujang, mè rang.', 69000],
        ['Cánh gà chiên mắm', '6 cánh, mắm tỏi.', 65000],
        ['Gà viên popcorn', 'Một hộp vừa.', 39000],
      ] },
      { section: 'Ăn kèm', category: 'Đồ ăn vặt', items: [
        ['Khoai tây chiên', 'Phần vừa.', 25000],
        ['Khoai lắc phô mai', '', 30000],
        ['Salad bắp cải', '', 15000],
        ['Pepsi lon', '', 15000],
      ] },
    ],
  }),
  s(9, 'Trịnh Quốc Bảo', 'Lẩu nướng Bếp Than', `Toà S3.02 ${AREA}`, 'Tầng 1, ki-ốt 09', {
    radiusKm: 2.5, fee: 15000, freeFrom: 300000, hours: [['16:00', '22:30']],
    menu: [
      { section: 'Lẩu', category: 'Lẩu & nướng', items: [
        ['Lẩu thái hải sản (2 người)', 'Tôm, mực, nghêu, rau, bún.', 259000],
        ['Lẩu gà lá é (2 người)', 'Gà ta, lá é, nấm.', 239000],
        ['Lẩu riêu cua bắp bò', 'Riêu cua đồng, bắp bò Úc.', 249000],
      ] },
      { section: 'Nướng', category: 'Lẩu & nướng', items: [
        ['Ba chỉ nướng sốt Hàn', '300 g, kèm rau cuốn.', 129000],
        ['Sườn nướng mật ong', '400 g.', 149000],
        ['Mực nướng sa tế', 'Một con vừa.', 119000, null, 'soldOut'],
        ['Rau nấm nướng', '', 49000],
      ] },
    ],
  }),
  s(10, 'Đinh Thị Mai', 'Bánh cuốn Thanh Trì Mai', `Toà S1.05 ${AREA}`, 'Kiot 03, sảnh toà S1.05', {
    radiusKm: 2, fee: 5000, freeFrom: 100000, hours: [['06:00', '11:00']],
    menu: [
      { section: 'Bánh cuốn', category: 'Bún & phở', items: [
        ['Bánh cuốn chả quế', 'Bánh tráng mỏng, chả quế, hành phi.', 35000, 'size'],
        ['Bánh cuốn nhân thịt', 'Thịt băm, mộc nhĩ.', 35000, 'size'],
        ['Bánh cuốn trứng', 'Trứng gà ta.', 40000],
      ] },
      { section: 'Món thêm', category: 'Bún & phở', items: [
        ['Chả quế thêm', '', 15000],
        ['Trứng vịt lộn', 'Một quả.', 10000],
      ] },
    ],
  }),
  s(11, 'Tạ Minh Khôi', 'Cà phê Muối Tầng 1', `Toà S2.05 ${AREA}`, 'Sảnh toà S2.05', {
    radiusKm: 3, fee: 5000, freeFrom: 80000, hours: [['06:30', '22:00']],
    menu: [
      { section: 'Cà phê', category: 'Cà phê', items: [
        ['Cà phê muối', 'Robusta phin, kem muối.', 30000, 'sugar'],
        ['Bạc xỉu', '', 29000, 'sugar'],
        ['Cà phê đen đá', '', 22000, 'sugar'],
        ['Cà phê cốt dừa', 'Cốt dừa đá xay.', 39000],
      ] },
      { section: 'Trà và nước', category: 'Trà & nước giải khát', items: [
        ['Trà đào cam sả', '', 35000, 'sugar'],
        ['Trà ô long sữa', '', 35000, 'sugar'],
        ['Nước dừa tươi', 'Dừa Bến Tre.', 30000],
      ] },
    ],
  }),
  s(12, 'Mạc Thị Lan', 'Tiệm bánh Mây Trắng', `Toà S3.03 ${AREA}`, 'Ki-ốt 02, tầng 1', {
    radiusKm: 3, fee: 8000, freeFrom: 150000, hours: [['07:00', '21:00']],
    menu: [
      { section: 'Bánh ngọt', category: 'Bánh ngọt', items: [
        ['Bánh bông lan trứng muối', 'Một hộp 4 miếng.', 65000],
        ['Bánh su kem (6 chiếc)', '', 45000],
        ['Tiramisu cốc', '', 39000],
        ['Bánh flan caramel', 'Hai hũ.', 25000],
        ['Croissant bơ', 'Nướng mỗi sáng.', 32000],
      ] },
      { section: 'Đồ uống', category: 'Trà & nước giải khát', items: [
        ['Sữa tươi trân châu', '', 30000, 'sugar'],
        ['Trà hoa cúc mật ong', '', 28000],
      ] },
    ],
  }),
  s(13, 'Kiều Văn Đạt', 'Mì Quảng Bà Mua', `Toà S1.01 ${AREA}`, 'Kiot 04, sảnh toà S1.01', {
    radiusKm: 3, fee: 7000, freeFrom: 150000, hours: [['06:30', '13:30'], ['17:00', '20:30']],
    menu: [
      { section: 'Mì Quảng', category: 'Mì & miến', items: [
        ['Mì Quảng tôm thịt', 'Bánh tráng mè, đậu phộng.', 45000, 'size'],
        ['Mì Quảng gà', 'Gà ta, trứng cút.', 45000, 'size'],
        ['Mì Quảng cá lóc', '', 50000],
      ] },
      { section: 'Món thêm', category: 'Mì & miến', items: [
        ['Bánh tráng mè', 'Một chiếc to.', 8000],
        ['Trứng cút (5 quả)', '', 10000],
      ] },
    ],
  }),
  s(14, 'Phùng Thị Ngọc', 'Cơm văn phòng Ngọc Bích', `Toà S2.03 ${AREA}`, 'Kiot 07, sảnh toà S2.03', {
    radiusKm: 3, fee: 5000, freeFrom: 120000, hours: [['10:00', '14:00'], ['17:00', '20:00']],
    menu: [
      { section: 'Cơm suất', category: 'Cơm', items: [
        ['Cơm gà xối mỡ', 'Đùi gà giòn, cơm chiên nước mắm.', 45000, 'size'],
        ['Cơm bò lúc lắc', 'Bò Úc, khoai tây.', 59000, 'size'],
        ['Cơm chay thập cẩm', 'Đậu phụ, nấm, rau củ.', 40000],
        ['Cơm tôm rim thịt', '', 48000, 'size'],
        ['Cơm cá thu sốt cà', '', 50000],
      ] },
      { section: 'Canh', category: 'Cơm', items: [
        ['Canh bí đỏ thịt bằm', '', 15000],
        ['Canh rong biển', '', 15000],
      ] },
    ],
  }),
  s(15, 'Ông Văn Thịnh', 'Pizza Lò Củi Thịnh', `Toà S3.02 ${AREA}`, 'Shophouse SH-21', {
    radiusKm: 3, fee: 15000, freeFrom: 250000, hours: [['10:30', '22:00']],
    menu: [
      { section: 'Pizza', category: 'Đồ ăn vặt', items: [
        ['Pizza hải sản 25 cm', 'Tôm, mực, sốt cà chua.', 159000],
        ['Pizza bò BBQ 25 cm', '', 149000],
        ['Pizza 4 loại phô mai 25 cm', '', 139000],
        ['Pizza xúc xích Ý 25 cm', '', 129000],
      ] },
      { section: 'Mỳ Ý và món phụ', category: 'Mì & miến', items: [
        ['Mỳ Ý sốt bò bằm', '', 79000],
        ['Mỳ Ý kem nấm', '', 75000],
        ['Bánh mì bơ tỏi', '4 lát.', 35000],
      ] },
    ],
  }),
  s(16, 'Châu Thị Diệu', 'Sinh tố Trái Cây Diệu', `Toà S1.05 ${AREA}`, 'Ki-ốt sân chơi phía Bắc', {
    radiusKm: 3, fee: 4000, freeFrom: 70000, hours: [['08:00', '21:30']],
    menu: [
      { section: 'Sinh tố', category: 'Nước ép & sinh tố', items: [
        ['Sinh tố xoài', '', 35000, 'sugar'],
        ['Sinh tố dâu', '', 40000, 'sugar'],
        ['Sinh tố mãng cầu', '', 40000, 'sugar'],
      ] },
      { section: 'Nước ép và trái cây', category: 'Nước ép & sinh tố', items: [
        ['Nước ép dưa hấu', '', 30000],
        ['Nước ép cần tây táo', '', 40000],
        ['Hộp trái cây tươi', 'Dưa, xoài, ổi, nho.', 45000],
      ] },
    ],
  }),
  s(17, 'Lục Văn Nam', 'Bánh xèo Miền Tây Út Nam', `Toà S2.05 ${AREA}`, 'Kiot 02, sảnh toà S2.05', {
    radiusKm: 2.5, fee: 7000, freeFrom: 150000, hours: [['15:00', '21:30']],
    menu: [
      { section: 'Bánh xèo', category: 'Đồ ăn vặt', items: [
        ['Bánh xèo tôm thịt', 'Một chiếc to, rau sống, nước mắm.', 45000],
        ['Bánh khọt (10 cái)', 'Tôm tươi, nước cốt dừa.', 50000],
        ['Bánh xèo chay', 'Nấm, giá, đậu.', 40000],
      ] },
      { section: 'Gỏi cuốn', category: 'Đồ ăn vặt', items: [
        ['Gỏi cuốn tôm thịt (4 cuốn)', '', 40000],
        ['Bò bía (4 cuốn)', '', 30000],
      ] },
    ],
  }),
  s(18, 'Viên Thị Hạnh', 'Hủ tiếu Nam Vang Hạnh', `Toà S3.03 ${AREA}`, 'Kiot 06, sảnh toà S3.03', {
    radiusKm: 3, fee: 6000, freeFrom: 120000, hours: [['06:00', '13:00'], ['17:00', '21:00']],
    menu: [
      { section: 'Hủ tiếu', category: 'Mì & miến', items: [
        ['Hủ tiếu Nam Vang', 'Tôm, gan, thịt bằm, trứng cút.', 50000, 'size'],
        ['Hủ tiếu khô', 'Nước dùng riêng.', 50000, 'size'],
        ['Mì hoành thánh', '', 45000],
      ] },
      { section: 'Món thêm', category: 'Mì & miến', items: [
        ['Hoành thánh chiên (5 cái)', '', 25000],
        ['Quẩy giòn', '', 6000],
      ] },
    ],
  }),
  s(19, 'Giang Thị Thu', 'Trà sữa Mộc Trà', `Toà S1.01 ${AREA}`, 'Shophouse SH-03', {
    radiusKm: 3, fee: 4000, freeFrom: 80000, hours: [['09:00', '22:30']],
    menu: [
      { section: 'Trà sữa', category: 'Trà sữa', items: [
        ['Trà sữa trân châu đường đen', '', 39000, 'sugar'],
        ['Trà sữa khoai môn', '', 39000, 'sugar'],
        ['Hồng trà kem cheese', '', 42000, 'sugar'],
        ['Trà sữa matcha', '', 42000, 'sugar'],
      ] },
      { section: 'Topping', category: 'Trà sữa', items: [
        ['Trân châu trắng', '', 8000],
        ['Thạch phô mai', '', 10000],
        ['Pudding trứng', '', 10000],
      ] },
    ],
  }),
  s(20, 'Âu Văn Lực', 'Bún đậu mắm tôm Cô Lực', `Toà S2.03 ${AREA}`, 'Kiot 05, sảnh toà S2.03', {
    radiusKm: 2.5, fee: 6000, freeFrom: 150000, hours: [['10:00', '14:00'], ['16:30', '21:00']],
    menu: [
      { section: 'Bún đậu', category: 'Bún & phở', items: [
        ['Bún đậu thập cẩm', 'Đậu rán, chả cốm, thịt luộc, lòng.', 65000, 'extra'],
        ['Bún đậu chả cốm', '', 50000, 'extra'],
        ['Bún đậu cơ bản', 'Đậu rán, thịt luộc.', 40000],
      ] },
      { section: 'Món thêm', category: 'Bún & phở', items: [
        ['Chả cốm thêm', '', 20000],
        ['Nem chua rán (5 cái)', '', 35000],
        ['Trà quất', '', 15000],
      ] },
    ],
  }),
]

const FAMILY = ['Nguyễn', 'Trần', 'Lê', 'Phạm', 'Vũ', 'Đặng', 'Bùi', 'Đỗ', 'Hồ', 'Ngô', 'Dương', 'Lý']
const GIVEN = ['Thảo Vy', 'Gia Bảo', 'Khánh Linh', 'Minh Quân', 'Ngọc Hân', 'Hoàng Nam', 'Phương Anh', 'Đức Thắng', 'Thanh Tâm', 'Quốc Việt', 'Bảo Ngọc', 'Tuấn Kiệt']
// towers the address lookup knows (the same as seed.mjs)
const TOWERS = ['S1.03', 'S2.01', 'S3.01', 'S1.03', 'S1.01', 'S2.03', 'S3.02', 'S1.05', 'S2.05', 'S3.03', 'S3.01', 'S2.01']

export const moreCustomers = (AREA) =>
  FAMILY.map((family, i) => {
    const n = i + 5
    return {
      email: `demo.khach${n}@example.com`,
      name: `${family} ${GIVEN[i]}`,
      phone: `09000002${String(n).padStart(2, '0')}`,
      place: `Toà ${TOWERS[i]} ${AREA}`,
      detail: `Căn ${String(3 + ((i * 7) % 30)).padStart(2, '0')}${String(1 + ((i * 5) % 18)).padStart(2, '0')}`,
    }
  })

/** Reviews of the busy wave: mostly good, some honest complaints, so ratings vary between shops. */
export const busyReviews = [
  [5, 'Ngon, giao nhanh, đồ còn nóng.'], [5, 'Đóng gói sạch sẽ, sẽ đặt lại.'], [4, 'Vị ổn, hơi ít rau.'], [5, ''],
  [4, 'Ngon nhưng giờ trưa chờ hơi lâu.'], [5, 'Quán thân thiện, gọi điện xác nhận rất lịch sự.'], [3, 'Hơi mặn so với khẩu vị mình.'],
  [5, 'Phần ăn đầy đặn, đáng tiền.'], [4, ''], [5, 'Lần thứ năm đặt rồi, vẫn chất lượng như cũ.'], [2, 'Giao thiếu đũa thìa, đồ hơi nguội.'],
  [4, 'Ổn áp, giá hợp lý.'], [5, 'Shipper là chủ quán luôn, giao tận cửa căn hộ.'], [3, 'Bình thường, không có gì đặc biệt.'],
  [5, 'Nước dùng đậm đà.'], [4, 'Ngon, lần sau cho thêm sốt nhé.'],
]
export const busyReplies = [
  'Cảm ơn bạn nhiều, hẹn gặp lại bạn lần sau!', 'Quán xin lỗi vì để bạn chờ, giờ cao điểm quán sẽ chuẩn bị sớm hơn.',
  'Cảm ơn góp ý, quán sẽ điều chỉnh độ mặn ạ.', 'Xin lỗi bạn về sự thiếu sót, lần sau quán sẽ kiểm tra kỹ hơn.',
]
