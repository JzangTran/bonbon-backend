#!/usr/bin/env node
// Everything else a lived-in bonbon has, on top of seed.mjs (owner 2026-11-19: "every kind of data should be plentiful"):
// chats with quick replies and auto-replies, vouchers, second addresses, favourites and hidden shops, order complaints at every
// stage, no-show reports, support tickets, contact requests, reports of reviews, messages and shops, personal-data requests,
// help articles, penalties with appeals, payouts and adjustments, dish category corrections, shop applications waiting for
// review (and one rejected), notification preferences and quiet hours. Every section checks what exists and runs once.
// It drives the public API, so notifications, ledger postings and audit entries happen as in real use.
//
// Usage (Git Bash), after seed.mjs, with the shops open (seed.mjs --always-open first):
//   ADMIN_EMAIL=... ADMIN_PASSWORD=... node demo-data/life.mjs
// Env: BONBON_API (default http://localhost:8080), BONBON_MAILPIT (default http://localhost:8025), DEMO_PASSWORD,
//      DEMO_AREA, DEMO_REDIS_CONTAINER (as in seed.mjs: five new sellers register here).

import { execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'

const API = (process.env.BONBON_API ?? 'http://localhost:8080').replace(/\/+$/, '')
const MAILPIT = (process.env.BONBON_MAILPIT ?? 'http://localhost:8025').replace(/\/+$/, '')
const PASSWORD = process.env.DEMO_PASSWORD ?? 'bonbon-demo-2027'
const AREA = process.env.DEMO_AREA ?? 'Vinhomes Smart City Tây Mỗ'
const ADMIN_EMAIL = process.env.ADMIN_EMAIL
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD
const REDIS_CONTAINER = process.env.DEMO_REDIS_CONTAINER
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64')

const log = (...a) => console.log(...a)
const skipped = []
const soft = (where) => (e) => {
  skipped.push(`${where}: ${String(e.message ?? e).slice(0, 110)}`)
  return null
}

// ---------------------------------------------------------------- http

class ApiError extends Error {
  constructor(status, body, where) {
    super(`${where} -> ${status} ${body?.code ?? ''} ${body?.detail ?? JSON.stringify(body)}`)
    this.status = status
  }
}

async function call(method, path, { body, token, form, headers = {} } = {}) {
  const init = { method, headers: { ...headers } }
  if (token) init.headers.Authorization = `Bearer ${token}`
  if (form) init.body = form
  else if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }
  let res = await fetch(API + path, init)
  for (let retry = 0; res.status === 429 && retry < 12; retry++) {
    if (path === '/api/auth/register' && REDIS_CONTAINER) {
      const keys = execFileSync('docker', ['exec', REDIS_CONTAINER, 'redis-cli', '--scan', '--pattern', 'register_count:ip:*'], { encoding: 'utf8' }).split(/\s+/).filter(Boolean)
      if (keys.length) execFileSync('docker', ['exec', REDIS_CONTAINER, 'redis-cli', 'del', ...keys])
    } else {
      const wait = Number(res.headers.get('Retry-After')) || 10
      log(`  … rate limited on ${path}, waiting ${wait}s`)
      await new Promise((r) => setTimeout(r, wait * 1000))
    }
    res = await fetch(API + path, init)
  }
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  if (!res.ok) throw new ApiError(res.status, data, `${method} ${path}`)
  return data
}

async function login(email, role) {
  let data = await call('POST', '/api/auth/login', { body: { email, password: PASSWORD } })
  if (data.needsRoleSelection) data = await call('POST', '/api/auth/select-role', { body: { roleToken: data.roleToken, role } })
  return data.tokens.accessToken
}

const items = (r) => (Array.isArray(r) ? r : (r?.items ?? []))
const png = (name = 'demo.png') => {
  const f = new FormData()
  f.append('file', new Blob([PNG], { type: 'image/png' }), name)
  return f
}
const daysFromNow = (d) => new Date(Date.now() + d * 86400000).toISOString()

// ---------------------------------------------------------------- who is who

async function people(admin) {
  const shops = []
  for (let n = 1; n <= 20; n++) {
    const email = `demo.seller${n}@example.com`
    const token = await login(email, 'SELLER').catch(() => null)
    if (!token) continue
    const view = await call('GET', '/api/merchant/shop', { token })
    const name = view.shop?.name ?? view.name
    const rows = items(await call('GET', `/api/admin/merchant-approval/requests?status=APPROVED&q=${encodeURIComponent(name)}`, { token: admin }))
    const id = rows.find((r) => r.shop.name === name)?.shop.vendorId
    if (id) shops.push({ n, email, token, name, id })
  }
  const customers = []
  for (let n = 1; n <= 16; n++) {
    const email = `demo.khach${n}@example.com`
    const token = await login(email, 'CUSTOMER').catch(() => null)
    if (!token) continue
    const addresses = await call('GET', '/api/account/addresses', { token })
    customers.push({ n, email, token, addressId: addresses.find((a) => a.isDefault)?.id ?? addresses[0]?.id, addresses })
  }
  return { shops, customers }
}

// ---------------------------------------------------------------- sections

/** A work address for half the customers, and a few favourites and hidden shops. */
async function addressesAndTastes(customers, shops) {
  const names = ['Minh Anh', 'Quang Huy', 'Thu Hà', 'Đức Long', 'Thảo Vy', 'Gia Bảo', 'Khánh Linh', 'Minh Quân', 'Ngọc Hân', 'Hoàng Nam', 'Phương Anh', 'Đức Thắng', 'Thanh Tâm', 'Quốc Việt', 'Bảo Ngọc', 'Tuấn Kiệt']
  for (const c of customers.filter((c) => c.n % 2 === 0 && c.addresses.length < 2)) {
    const suggestions = await call('GET', `/api/geo/autocomplete?input=${encodeURIComponent(`Toà S3.01 ${AREA}`)}`, { token: c.token }).catch(soft('address lookup'))
    if (!suggestions?.length) continue
    await call('POST', '/api/account/addresses', { token: c.token, body: {
      label: 'Công ty', placeId: suggestions[0].placeId, detail: `Tầng ${3 + (c.n % 9)}, văn phòng ${100 + c.n}`, recipientName: names[c.n - 1],
      recipientPhone: `09000002${String(c.n).padStart(2, '0')}`, makeDefault: false,
    } }).catch(soft('address'))
  }
  for (const c of customers) {
    for (let k = 0; k < 3; k++) await call('PUT', `/api/account/favorite-shops/${shops[(c.n * 3 + k * 5) % shops.length].id}`, { token: c.token }).catch(() => {})
  }
  for (const c of customers.slice(2, 5)) await call('PUT', `/api/account/hidden-shops/${shops[(c.n * 7) % shops.length].id}`, { token: c.token }).catch(() => {})
  for (const c of customers.slice(0, 6)) {
    await call('PUT', '/api/me/quiet-hours', { token: c.token, body: { start: '22:30', end: '07:00', timeZone: 'Asia/Ho_Chi_Minh' } }).catch(soft('quiet hours'))
  }
  for (const c of customers.slice(6, 10)) {
    await call('PUT', '/api/me/notification-preferences', { token: c.token, body: { changes: [
      { category: 'ORDER_PROGRESS', channel: 'EMAIL', enabled: false }, { category: 'CHAT_MESSAGES', channel: 'EMAIL', enabled: false },
    ] } }).catch(soft('preferences'))
  }
  log('  + addresses, favourites, hidden shops, quiet hours, preferences')
}

const QUICK = [
  ['xin-chao', 'Chào bạn, quán đã nhận tin. Bạn cần quán hỗ trợ gì ạ?'],
  ['dang-giao', 'Đơn của bạn đang trên đường giao, khoảng 10 phút nữa tới sảnh ạ.'],
  ['het-mon', 'Món này hôm nay quán hết rồi ạ, bạn đổi giúp quán món khác nhé.'],
  ['cam-on', 'Cảm ơn bạn đã ủng hộ quán, chúc bạn ngon miệng!'],
]
const AUTO = 'Quán đang bận chuẩn bị đơn, sẽ trả lời bạn trong ít phút. Cần gấp bạn gọi số trên trang quán nhé.'

/** Quick replies and an auto-reply for every shop, vouchers for most. */
async function shopTools(shops) {
  for (const s of shops) {
    const have = items(await call('GET', '/api/merchant/quick-replies', { token: s.token }))
    for (const [shortcut, text] of QUICK) if (!have.some((q) => q.shortcut === shortcut)) await call('POST', '/api/merchant/quick-replies', { token: s.token, body: { shortcut, text } }).catch(soft('quick reply'))
    if (s.n % 3 === 0) await call('PUT', '/api/merchant/shop/auto-reply', { token: s.token, body: { enabled: true, message: AUTO } }).catch(soft('auto-reply'))
    const vouchers = items(await call('GET', '/api/merchant/vouchers', { token: s.token }))
    if (vouchers.length) continue
    const code = s.name.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/gi, 'd').replace(/[^A-Za-z]/g, '').toUpperCase().slice(0, 6)
    const plan = [
      { code: `${code}10`, discountType: 'PERCENT', discountValue: 10, maxDiscount: 20000, minOrderValue: 80000, validFrom: daysFromNow(-3), validTo: daysFromNow(30), usageLimit: 200, maxPerCustomer: 3 },
      { code: `${code}15K`, discountType: 'FIXED', discountValue: 15000, minOrderValue: 120000, validFrom: daysFromNow(-1), validTo: daysFromNow(14), usageLimit: 100, maxPerCustomer: 1 },
      { code: `${code}MOI`, discountType: 'PERCENT', discountValue: 20, maxDiscount: 30000, minOrderValue: 60000, validFrom: daysFromNow(2), validTo: daysFromNow(20), usageLimit: 50, maxPerCustomer: 1 },
    ].slice(0, 1 + (s.n % 3))
    for (const v of plan) await call('POST', '/api/merchant/vouchers', { token: s.token, body: v }).catch(soft(`voucher ${s.name}`))
  }
  log('  + quick replies, auto-replies, vouchers')
}

const CHATS = [
  ['Quán ơi còn mở không ạ? Mình muốn đặt 3 suất cho văn phòng.', 'Dạ quán còn mở tới 20h30 ạ, bạn đặt qua app là quán làm ngay.', 'Ok quán, giao giúp mình lên tầng 12 nhé.', 'Dạ quán ghi chú rồi ạ.'],
  ['Món này có cay không quán?', 'Dạ mức vừa thôi ạ, bạn ghi chú "không cay" là quán bỏ ớt riêng.', 'Cảm ơn quán!'],
  ['Đơn mình đặt 15 phút rồi chưa thấy quán nhận ạ.', 'Xin lỗi bạn, giờ trưa quán hơi đông, quán nhận ngay đây ạ.', 'Vâng, mình đợi được.', 'Quán đang giao rồi ạ, khoảng 10 phút nữa tới.'],
  ['Quán có xuất hoá đơn công ty không ạ?', 'Dạ có ạ, bạn gửi giúp quán tên công ty và mã số thuế qua đây nhé.'],
  ['Hôm qua mình đặt thiếu một hộp canh ạ.', 'Quán xin lỗi bạn, bạn báo trong mục đơn hàng để được hoàn tiền giúp quán nhé, lần sau quán kiểm tra kỹ hơn.', 'Mình báo rồi, cảm ơn quán.'],
  ['Cho mình hỏi quán có món chay không?', 'Dạ có cơm chay thập cẩm và bánh xèo chay ạ.', 'Tuyệt, mai mình đặt.'],
  ['Mình đặt bàn tiệc sinh nhật 10 người được không quán?', 'Dạ quán chỉ bán mang về thôi ạ, nhưng bạn đặt trước một ngày quán làm suất lớn được.', 'Vậy mai mình nhắn lại nhé.'],
]

/** Conversations between customers and shops, a few with the shop's reply still unread. */
async function chats(customers, shops) {
  const first = await call('GET', '/api/conversations?size=1', { token: customers[0].token })
  if (first.total >= 3) return log('  = chats already there')
  let made = 0
  for (let i = 0; i < 34; i++) {
    const c = customers[i % customers.length]
    const s = shops[(i * 3 + 1) % shops.length]
    const script = CHATS[i % CHATS.length]
    const opened = await call('POST', `/api/shops/${s.id}/messages`, { token: c.token, body: { text: script[0] } }).catch(soft('chat open'))
    if (!opened) continue
    for (const [k, text] of script.slice(1).entries()) {
      const shopTurn = k % 2 === 0
      await call('POST', `/api/conversations/${opened.conversationId}/messages`, { token: shopTurn ? s.token : c.token, body: { text } }).catch(soft('chat'))
    }
    if (i % 3) await call('POST', `/api/conversations/${opened.conversationId}/read`, { token: c.token }).catch(() => {})
    await call('POST', `/api/conversations/${opened.conversationId}/read`, { token: s.token }).catch(() => {})
    made++
  }
  log(`  + ${made} conversations`)
}

async function placeOrder(c, s, pick = 0) {
  const menu = await call('GET', `/api/vendors/${s.id}/menu`, { token: c.token })
  const list = menu.sections.flatMap((x) => x.items).filter((i) => !i.soldOut)
  const lines = [list[pick % list.length], list[(pick + 1) % list.length]].filter(Boolean).map((i, n) => ({
    menuItemId: i.id, quantity: n === 0 ? 2 : 1,
    optionIds: (i.optionGroups ?? []).flatMap((g) => (g.min > 0 ? [((g.options.find((o) => o.defaultChoice) ?? g.options[0]).id)] : [])),
  }))
  return call('POST', '/api/orders', { token: c.token, headers: { 'Idempotency-Key': randomUUID() }, body: { vendorId: s.id, addressId: c.addressId, paymentMethod: 'COD', items: lines } })
}
async function advance(o, s, to) {
  const path = ['CONFIRMED', 'PREPARING', 'OUT_FOR_DELIVERY', 'DELIVERED']
  for (const step of path.slice(0, path.indexOf(to) + 1)) {
    if (step === 'CONFIRMED') await call('POST', `/api/merchant/orders/${o.id}/confirm`, { token: s.token })
    else await call('POST', `/api/merchant/orders/${o.id}/status`, { token: s.token, body: { to: step } })
  }
}

/** Complaints at every stage (waiting for the shop, accepted, disputed, decided) and no-show reports. */
async function complaints(customers, shops, admin) {
  const existing = await call('GET', '/api/admin/order-cases?size=1', { token: admin })
  if (existing.total >= 6) return log('  = complaints already there')
  const types = ['MISSING_ITEM', 'WRONG_ITEM', 'QUALITY']
  const notes = ['Thiếu một hộp trong túi.', 'Giao nhầm món, mình đặt món khác.', 'Đồ ăn bị nguội và hơi chua.']
  const opened = []
  for (let i = 0; i < 10; i++) {
    const c = customers[(i + 4) % customers.length]
    const s = shops[(i * 5 + 2) % shops.length]
    const o = await placeOrder(c, s, i).catch(soft(`complaint order ${s.name}`))
    if (!o) continue
    const ok = await advance(o, s, 'DELIVERED').then(() => true, soft('advance'))
    if (!ok) continue
    const detail = await call('GET', `/api/orders/${o.id}`, { token: c.token })
    const photo = await call('POST', `/api/orders/${o.id}/case-photos`, { token: c.token, form: png('case.png') }).catch(soft('case photo'))
    const kase = await call('POST', `/api/orders/${o.id}/incident`, { token: c.token, body: {
      type: types[i % 3], lines: [{ orderItemId: detail.items[0].id, quantity: 1 }], note: notes[i % 3], photoKeys: photo ? [photo.photoKey] : [],
    } }).catch(soft('incident'))
    if (kase) opened.push({ kase, s, i })
  }
  for (const { kase, s, i } of opened) {
    if (i % 4 === 1) await call('POST', `/api/merchant/order-cases/${kase.id}/accept`, { token: s.token }).catch(soft('accept case'))
    if (i % 4 === 2 || i % 4 === 3) await call('POST', `/api/merchant/order-cases/${kase.id}/dispute`, { token: s.token, body: { note: 'Quán đã kiểm tra camera lúc đóng gói, túi đủ món.' } }).catch(soft('dispute case'))
  }
  for (const { kase, i } of opened.filter((x) => x.i % 4 === 2)) {
    await call('POST', `/api/admin/order-cases/${kase.id}/decide`, { token: admin, body: {
      outcome: i % 8 === 2 ? 'UPHELD' : 'DISMISSED', reason: i % 8 === 2 ? 'Ảnh của khách cho thấy thiếu món.' : 'Không đủ bằng chứng, quán có video đóng gói.',
    } }).catch(soft('decide case'))
  }
  log(`  + ${opened.length} complaints`)
}

/** Orders on their way, so that the shop can report a no-show once the minimum wait (10 minutes) is over. */
async function startNoShows(customers, shops, admin) {
  const existing = items(await call('GET', '/api/admin/order-cases?size=50', { token: admin })).filter((c) => /NO_SHOW/.test(c.type))
  if (existing.length) return []
  const out = []
  for (let k = 0; k < 3; k++) {
    const c = customers[(k + 9) % customers.length]
    const s = shops[(k * 4 + 3) % shops.length]
    const o = await placeOrder(c, s, k).catch(soft('no-show order'))
    if (o && (await advance(o, s, 'OUT_FOR_DELIVERY').then(() => true, soft('advance')))) out.push({ o, c, s, k, at: Date.now() })
  }
  return out
}

async function finishNoShows(list) {
  if (!list.length) return
  const wait = list[0].at + 10.5 * 60000 - Date.now()
  if (wait > 0) {
    log(`  … waiting ${Math.ceil(wait / 60000)} min for the no-show wait to pass`)
    await new Promise((r) => setTimeout(r, wait))
  }
  for (const { o, c, s, k } of list) {
    const reported = await call('POST', `/api/merchant/orders/${o.id}/no-show`, { token: s.token, body: { note: 'Gọi 3 lần không nghe máy, đợi ở sảnh 15 phút.' } }).catch(soft('no-show'))
    if (reported && k < 2) await call('POST', `/api/orders/${o.id}/no-show-answer`, { token: c.token, body: { answer: k ? 'SHOP_NEVER_CAME' : 'UNABLE', note: k ? 'Mình ở nhà cả buổi, không ai gọi.' : 'Mình bận họp không nghe được, xin lỗi quán.' } }).catch(soft('no-show answer'))
  }
  log(`  + ${list.length} no-show reports`)
}

const TICKETS = [
  ['Không áp được mã giảm giá', 'Mình nhập mã của quán nhưng báo không hợp lệ, dù đơn đủ giá trị tối thiểu.'],
  ['Muốn đổi số điện thoại nhận hàng', 'Số cũ mình không dùng nữa, đổi ở đâu ạ?'],
  ['Đơn bị huỷ nhưng chưa thấy hoàn tiền', 'Đơn chuyển khoản bị quán từ chối từ hôm qua, mình chưa nhận lại tiền.'],
  ['Không nhận được thông báo đơn', 'Điện thoại mình không hiện thông báo khi quán nhận đơn.'],
  ['Hỏi về phí giao hàng', 'Sao cùng toà mà quán này thu phí giao, quán kia không thu ạ?'],
  ['Góp ý tính năng', 'Mong app có chỗ đặt trước cho buổi trưa ngày mai.'],
]
const SELLER_TICKETS = [
  ['Đối soát tuần này bị lệch', 'Số tiền chi trả tuần này ít hơn tổng đơn đã giao, nhờ kiểm tra giúp.'],
  ['Không tải được ảnh món', 'Ảnh chụp từ điện thoại tải lên báo lỗi quá dung lượng.'],
  ['Muốn thêm một chi nhánh', 'Quán mở thêm điểm bán ở toà khác, đăng ký thế nào?'],
  ['Điểm phạt không đúng', 'Đơn bị tính lỗi quán nhưng do khách đặt sai địa chỉ.'],
]

/** Support tickets from customers and sellers at every stage, and contact requests from the public site. */
async function support(customers, shops, admin) {
  const existing = await call('GET', '/api/admin/support/tickets?size=1', { token: admin })
  if (existing.total >= 5) return log('  = support already there')
  const opened = []
  for (const [i, [subject, message]] of TICKETS.entries()) {
    const c = customers[(i * 2) % customers.length]
    const t = await call('POST', '/api/support/tickets', { token: c.token, body: { subject, message } }).catch(soft('ticket'))
    if (t) opened.push({ t, who: c, i })
  }
  for (const [i, [subject, message]] of SELLER_TICKETS.entries()) {
    const s = shops[(i * 3) % shops.length]
    const t = await call('POST', '/api/support/tickets', { token: s.token, body: { subject, message } }).catch(soft('ticket'))
    if (t) opened.push({ t, who: s, i: i + 10 })
  }
  for (const { t, who, i } of opened) {
    if (i % 3 === 2) continue // waiting for support
    await call('POST', `/api/admin/support/tickets/${t.id}/reply`, { token: admin, body: { body: 'Chào bạn, bonbon đã kiểm tra và xử lý giúp bạn. Bạn thử lại và báo lại nếu còn lỗi nhé.' } }).catch(soft('ticket reply'))
    if (i % 3 === 0) await call('POST', `/api/support/tickets/${t.id}/messages`, { token: who.token, body: { body: 'Mình thử lại được rồi, cảm ơn bonbon.' } }).catch(soft('ticket message'))
    if (i % 4 === 0) await call('POST', `/api/support/tickets/${t.id}/close`, { token: who.token }).catch(soft('ticket close'))
  }
  const CONTACTS = [
    ['Phạm Thị Hoa', 'CUSTOMER', 'ORDER', 'Tôi đặt đơn qua app của con nhưng không biết theo dõi ở đâu.'],
    ['Lương Văn Kiên', 'SELLER', 'SHOP', 'Quán tôi ở khu khác có mở bán trên bonbon được không?'],
    ['Công ty Ăn Ngon', 'OTHER', 'OTHER', 'Chúng tôi muốn hợp tác cung cấp bao bì cho các quán.'],
    ['Nguyễn Hải Yến', 'CUSTOMER', 'ACCOUNT', 'Tôi quên mật khẩu và không nhận được email đặt lại.'],
    ['Trần Đức Mạnh', 'SELLER', 'PAYMENT', 'Hoa hồng tính theo đơn hay theo tháng?'],
    ['Đỗ Mai Phương', 'OTHER', 'OTHER', 'Tôi là sinh viên, muốn phỏng vấn đội ngũ cho bài tập.'],
  ]
  for (const [i, [fullName, role, topic, message]] of CONTACTS.slice(0, 3).entries()) {
    await call('POST', '/api/public/contact-requests', { body: { fullName, email: `lienhe${i + 1}@example.com`, role, topic, message, captchaToken: 'demo' } }).catch(soft('contact'))
  }
  const contacts = items(await call('GET', '/api/admin/support/contact-requests?size=20', { token: admin }))
  for (const [i, r] of contacts.entries()) {
    if (i % 3 === 0) await call('POST', `/api/admin/support/contact-requests/${r.id}/answer`, { token: admin, body: { body: 'Cảm ơn bạn đã liên hệ, bonbon đã trả lời chi tiết qua email này.' } }).catch(soft('contact answer'))
    if (i % 3 === 1) await call('POST', `/api/admin/support/contact-requests/${r.id}/close`, { token: admin }).catch(soft('contact close'))
  }
  log(`  + ${opened.length} tickets, contact requests`)
}

/** Reports of reviews, messages and shops; some decided. */
async function reports(customers, shops, admin) {
  const existing = await call('GET', '/api/admin/reports?size=1', { token: admin })
  if (existing.total >= 4) return log('  = reports already there')
  const reasons = ['SPAM', 'OFFENSIVE', 'FALSE_INFORMATION', 'SCAM_OR_FRAUD', 'OTHER']
  let made = 0
  for (const [k, s] of shops.slice(0, 6).entries()) {
    const reviews = items(await call('GET', '/api/merchant/reviews?size=5', { token: s.token }))
    const target = reviews.find((r) => r.rating <= 3) ?? reviews[0]
    if (target) {
      const ok = await call('POST', '/api/reports', { token: s.token, body: { targetType: 'REVIEW', targetId: target.id, reasonCode: reasons[k % 5], note: 'Khách chưa từng nhận đơn này như mô tả.' } }).catch(soft('report review'))
      if (ok) made++
    }
  }
  for (const [k, c] of customers.slice(0, 4).entries()) {
    const ok = await call('POST', '/api/reports', { token: c.token, body: { targetType: 'SHOP', targetId: shops[(k * 6 + 5) % shops.length].id, reasonCode: k % 2 ? 'FALSE_INFORMATION' : 'OTHER', note: 'Ảnh món trên app khác xa món thật.' } }).catch(soft('report shop'))
    if (ok) made++
  }
  const conv = items(await call('GET', '/api/conversations?size=5', { token: shops[1].token }))
  for (const cv of conv.slice(0, 2)) {
    const msgs = await call('GET', `/api/conversations/${cv.id}/messages?size=5`, { token: shops[1].token }).catch(() => null)
    const m = items(msgs).find((x) => !x.mine)
    if (m) {
      const ok = await call('POST', '/api/reports', { token: shops[1].token, body: { targetType: 'MESSAGE', targetId: m.id, reasonCode: 'SPAM', note: 'Nhắn quảng cáo nhiều lần.' } }).catch(soft('report message'))
      if (ok) made++
    }
  }
  const open = items(await call('GET', '/api/admin/reports?size=20', { token: admin }))
  for (const [i, r] of open.entries()) {
    if (i % 3 === 2) continue
    const hide = i % 3 === 1 && r.targetType !== 'SHOP'
    await call('POST', `/api/admin/reports/${r.id}/decide`, { token: admin, body: { action: hide ? 'HIDE' : 'DISMISS', reason: hide ? 'Nội dung vi phạm quy tắc cộng đồng.' : 'Không thấy vi phạm.' } }).catch(soft('decide report'))
  }
  log(`  + ${made} reports`)
}

/** Personal-data requests at every stage. */
async function dataRequests(customers, admin) {
  const existing = await call('GET', '/api/admin/data-requests?size=1', { token: admin })
  if (existing.total >= 3) return log('  = data requests already there')
  const plan = [
    ['CORRECT', 'Tên trên đơn cũ bị sai chính tả, nhờ sửa giúp.'], ['WITHDRAW', 'Tôi không muốn nhận email khuyến mãi nữa.'],
    ['RESTRICT', 'Tạm ngưng dùng địa chỉ cũ của tôi cho thống kê.'], ['OBJECT', 'Không đồng ý dùng lịch sử đơn để gợi ý quán.'],
    ['CORRECT', 'Số điện thoại trong đơn #12 không phải của tôi.'],
  ]
  for (const [i, [type, details]] of plan.entries()) await call('POST', '/api/me/data-requests', { token: customers[(i * 3 + 1) % customers.length].token, body: { type, details } }).catch(soft('data request'))
  const rows = items(await call('GET', '/api/admin/data-requests?size=20', { token: admin }))
  for (const [i, r] of rows.entries()) {
    if (i === 0) continue
    await call('PATCH', `/api/admin/data-requests/${r.id}`, { token: admin, body: { action: 'START' } }).catch(soft('data start'))
    if (i === 2 || i === 3) await call('PATCH', `/api/admin/data-requests/${r.id}`, { token: admin, body: { action: 'COMPLETE', note: 'Đã cập nhật theo yêu cầu.' } }).catch(soft('data complete'))
    if (i === 4) await call('PATCH', `/api/admin/data-requests/${r.id}`, { token: admin, body: { action: 'REJECT', note: 'Dữ liệu đơn hàng phải lưu theo quy định kế toán.' } }).catch(soft('data reject'))
  }
  log(`  + ${plan.length} personal-data requests`)
}

const ARTICLES = [
  ['CUSTOMER', 'Cách đặt món lần đầu', ['đặt món', 'bắt đầu'], 'Chọn địa chỉ giao ở đầu trang Khám phá, chọn quán, thêm món vào giỏ rồi bấm Đặt món.\n\nQuán xác nhận trong vài phút; bạn theo dõi đơn ở tab Hoạt động.'],
  ['CUSTOMER', 'Phí giao hàng được tính thế nào', ['phí giao', 'miễn phí'], 'Mỗi quán tự đặt phí giao và mức đơn được miễn phí giao. Bạn thấy phí trước khi đặt, trong giỏ hàng.'],
  ['CUSTOMER', 'Đơn thiếu món hoặc sai món', ['khiếu nại', 'hoàn tiền', 'thiếu món'], 'Trong 24 giờ sau khi nhận, mở đơn và chọn Báo vấn đề. Chọn món bị thiếu hoặc sai, thêm ảnh nếu có.\n\nQuán trả lời trong 12 giờ; nếu quán không đồng ý, bonbon sẽ xem xét.'],
  ['CUSTOMER', 'Huỷ đơn đã đặt', ['huỷ đơn'], 'Bạn huỷ được khi quán chưa nhận đơn. Sau khi quán nhận, hãy nhắn quán qua mục Tin nhắn.'],
  ['CUSTOMER', 'Dùng mã giảm giá', ['voucher', 'mã giảm giá'], 'Nhập mã ở giỏ hàng. Mỗi mã có giá trị đơn tối thiểu, hạn dùng và số lần dùng cho mỗi người.'],
  ['CUSTOMER', 'Thanh toán bằng chuyển khoản', ['chuyển khoản', 'thanh toán'], 'Chọn Chuyển khoản khi đặt, quét mã QR trong 15 phút. Đơn được xác nhận tự động khi tiền về.'],
  ['CUSTOMER', 'Vòng quay chọn món', ['vòng quay', 'chọn món'], 'Chưa biết ăn gì? Mở tab Vòng quay, bonbon chọn ngẫu nhiên một món từ các quán đang mở gần bạn.'],
  ['SELLER', 'Mở quán trên bonbon', ['mở quán', 'đăng ký'], 'Điền bốn bước: thông tin quán, giao hàng, kinh doanh và định danh. Hồ sơ được duyệt trong một ngày làm việc.'],
  ['SELLER', 'Nhận và cập nhật đơn', ['đơn hàng', 'xác nhận'], 'Đơn mới hiện ở trang Đơn hàng kèm âm báo. Nhận đơn, rồi chuyển lần lượt Đang chuẩn bị, Đang giao, Đã giao.'],
  ['SELLER', 'Hoa hồng và đối soát', ['hoa hồng', 'đối soát', 'chi trả'], 'Hoa hồng tính trên giá trị món của đơn đã giao. Số dư và lịch sử chi trả ở trang Doanh thu.'],
  ['SELLER', 'Điểm phạt và cách kháng nghị', ['điểm phạt', 'kháng nghị'], 'Tuần có tỷ lệ đơn lỗi do quán vượt ngưỡng bị cộng một điểm. Bạn kháng nghị được trong 7 ngày ở trang Hiệu suất.'],
  ['SELLER', 'Tạo voucher cho quán', ['voucher', 'khuyến mãi'], 'Vào Marketing, chọn Tạo voucher: phần trăm hoặc số tiền, đơn tối thiểu, thời hạn và số lượt.'],
  ['SELLER', 'Báo hết món trong ngày', ['hết món'], 'Bật Hết món ở thực đơn; món tự bán lại từ 4 giờ sáng hôm sau.'],
  ['ALL', 'Liên hệ đội hỗ trợ bonbon', ['hỗ trợ', 'liên hệ'], 'Gửi phiếu hỗ trợ trong app hoặc trang Liên hệ. Đội hỗ trợ trả lời trong giờ hành chính.'],
]

/** Help articles for customers and sellers, two left as drafts. */
async function helpArticles(admin) {
  const have = items(await call('GET', '/api/admin/help-articles?size=100', { token: admin })).map((a) => a.title)
  let made = 0
  for (const [i, [audience, title, keywords, body]] of ARTICLES.entries()) {
    if (have.includes(title)) continue
    const ok = await call('POST', '/api/admin/help-articles', { token: admin, body: { audience, title, keywords, body, status: i % 7 === 6 ? 'DRAFT' : 'PUBLISHED' } }).catch(soft('article'))
    if (ok) made++
  }
  log(`  + ${made} help articles`)
}

/** Penalties on four shops, two appealed, one appeal decided each way; payouts, a collection and adjustments. */
async function performanceAndMoney(shops, admin) {
  const marked = await call('GET', `/api/admin/shops/${shops[2].id}/penalties`, { token: admin })
  if (!(marked.penalties ?? []).length) {
    for (const [k, s] of [shops[2], shops[5], shops[9], shops[13]].entries()) {
      await call('POST', `/api/admin/shops/${s.id}/penalties`, { token: admin, body: { points: 1 + (k % 2), reason: k % 2 ? 'Nhiều đơn bị huỷ do quán hết món trong tuần.' : 'Giao trễ nhiều đơn giờ cao điểm.' } }).catch(soft('penalty'))
    }
    for (const s of [shops[2], shops[9]]) {
      const perf = await call('GET', '/api/merchant/performance', { token: s.token }).catch(() => null)
      const p = (perf?.penalties ?? [])[0]
      if (p) await call('POST', `/api/merchant/penalties/${p.id}/appeal`, { token: s.token, body: { reason: 'Các đơn trễ do thang máy toà bảo trì, có thông báo của ban quản lý.' } }).catch(soft('appeal'))
    }
    const appeals = items(await call('GET', '/api/admin/shop-penalties/appeals', { token: admin }).catch(() => []))
    for (const [i, a] of appeals.slice(0, 1).entries()) {
      await call('POST', `/api/admin/shop-penalties/${a.id ?? a.penaltyId}/appeal-decision`, { token: admin, body: { decision: i ? 'REJECT' : 'ACCEPT', reason: 'Có xác nhận của ban quản lý toà nhà.' } }).catch(soft('appeal decision'))
    }
    log('  + penalties and appeals')
  } else log('  = penalties already there')

  for (const [k, s] of shops.entries()) {
    const balance = await call('GET', `/api/admin/settlement/vendors/${s.id}`, { token: admin }).catch(() => null)
    if (!balance || balance.payable <= 0 || k % 3 === 2) continue
    // once per shop: skip when the ledger already has a demo payout
    const ledger = JSON.stringify(await call('GET', `/api/admin/settlement/vendors/${s.id}/ledger?size=100`, { token: admin }).catch(() => ''))
    if (ledger.includes('CK-DEMO-')) continue
    await call('POST', `/api/admin/settlement/vendors/${s.id}/entries`, { token: admin, headers: { 'Idempotency-Key': randomUUID() }, body: {
      type: 'PAYOUT', amount: Math.floor(balance.payable * 0.7), reference: `CK-DEMO-${String(100 + k).padStart(4, '0')}`, note: 'Chi trả tuần (dữ liệu demo).',
    } }).catch(soft('payout'))
    if (k % 5 === 1) await call('POST', `/api/admin/settlement/vendors/${s.id}/entries`, { token: admin, headers: { 'Idempotency-Key': randomUUID() }, body: { type: 'ADJUSTMENT', amount: 15000, note: 'Bù phí giao cho đơn khiếu nại (dữ liệu demo).' } }).catch(soft('adjustment'))
  }
  log('  + payouts and adjustments')
}

/** Dishes filed under the wrong category: one fixed by the shop, one appealed and decided, one still open. */
async function categoryCases(shops, admin) {
  const existing = await call('GET', '/api/admin/category-cases?size=1', { token: admin })
  if (existing.total >= 2) return log('  = category cases already there')
  const leaves = []
  const walk = (ns) => ns.forEach((n) => (n.children?.length ? walk(n.children) : leaves.push(n)))
  walk(await call('GET', '/api/categories'))
  const byName = (n) => leaves.find((l) => l.name === n)
  const plan = [['Gà viên popcorn', 'Cơm'], ['Mỳ Ý sốt bò bằm', 'Đồ ăn vặt'], ['Croissant bơ', 'Bánh mì & xôi'], ['Bánh flan caramel', 'Đồ ăn vặt']]
  for (const [i, [dish, wrong]] of plan.entries()) {
    let found = null
    for (const s of shops) {
      const menu = await call('GET', `/api/vendors/${s.id}/menu`, { token: s.token })
      const item = menu.sections.flatMap((x) => x.items).find((x) => x.name === dish)
      if (item) {
        found = { s, item }
        break
      }
    }
    if (!found) continue
    const suggested = byName(wrong) ?? leaves[0]
    const flagged = await call('POST', `/api/admin/dishes/${found.item.id}/flag`, { token: admin, body: { reason: 'Món có vẻ đặt sai ngành, nhờ quán kiểm tra.', suggestedCategoryId: suggested.id, deadlineAt: daysFromNow(5) } })
      .catch(() => call('POST', `/api/admin/dishes/${found.item.id}/flag`, { token: admin, body: { reason: 'Món có vẻ đặt sai ngành, nhờ quán kiểm tra.', suggestedCategoryId: byName('Bánh ngọt').id, deadlineAt: daysFromNow(5) } }))
      .catch(soft('flag dish'))
    if (!flagged || i % 2) continue
    // the shop answers half of them by choosing the category itself
    const kase = items(await call('GET', '/api/merchant/category-cases?size=10', { token: found.s.token }).catch(() => [])).find((c) => c.dishName === dish)
    if (kase) await call('POST', `/api/merchant/category-cases/${kase.id}/resolve`, { token: found.s.token, body: { categoryId: suggested.id } }).catch(soft('category resolve'))
  }
  log('  + dish category cases')
}

// ---------------------------------------------------------------- applications waiting for review

async function verificationToken(email) {
  for (let attempt = 0; attempt < 20; attempt++) {
    const found = await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${email}"`)}&limit=5`).then((r) => r.json())
    for (const m of found.messages ?? []) {
      const msg = await fetch(`${MAILPIT}/api/v1/message/${m.ID}`).then((r) => r.json())
      const match = /verify-email\?token=([A-Za-z0-9_-]+)/.exec(`${msg.Text ?? ''} ${msg.HTML ?? ''}`)
      if (match) return match[1]
    }
    await new Promise((r) => setTimeout(r, 500))
  }
  throw new Error(`No verification email for ${email} in Mailpit`)
}

const APPLICANTS = [
  [21, 'Hồ Thị Duyên', 'Bánh tráng trộn Duyên', 'PENDING'], [22, 'Mai Văn Phúc', 'Cháo dinh dưỡng Phúc', 'PENDING'],
  [23, 'Kha Thị Vân', 'Nem nướng Nha Trang Vân', 'PENDING'], [24, 'Tô Văn Hải', 'Ốc luộc Hải Béo', 'REJECTED'], [25, 'Lâm Ngọc Bích', 'Kem bơ Đà Lạt Bích', 'DRAFT'],
]

/** Shops waiting for the review queue (three), one rejected, one still a draft. */
async function applications(admin) {
  const pending = items(await call('GET', '/api/admin/merchant-approval/requests?status=PENDING', { token: admin }))
  if (pending.some((r) => APPLICANTS.some(([, , name]) => name === r.shop.name))) return log('  = applications already there')
  const docs = async (...types) => Promise.all(types.map(async (t) => (await call('GET', `/api/legal/documents/${t}`)).id))
  for (const [n, sellerName, shopName, end] of APPLICANTS) {
    const email = `demo.seller${n}@example.com`
    let token = await login(email, 'SELLER').catch(() => null)
    if (!token) {
      const ok = await call('POST', '/api/auth/register', { body: { email, password: PASSWORD, name: sellerName, role: 'SELLER', acceptedDocumentIds: await docs('SELLER_TERMS', 'PRIVACY_POLICY') } }).catch(soft(`register ${email}`))
      if (!ok) continue
      const vt = await verificationToken(email).catch(soft('verify'))
      if (!vt) continue
      await call('GET', `/api/auth/verify-email?token=${vt}`)
      token = await login(email, 'SELLER')
    }
    const suggestions = await call('GET', `/api/geo/autocomplete?input=${encodeURIComponent(`Toà S3.01 ${AREA}`)}`, { token })
    const place = await call('GET', `/api/geo/place?placeId=${encodeURIComponent(suggestions[0].placeId)}`, { token })
    const [terms, privacy] = await docs('SELLER_TERMS', 'PRIVACY_POLICY')
    const phone = `09000001${n}`
    const steps = [
      { name: shopName, phone, email, provinceCode: place.provinceCode, wardCode: place.wardCode, lat: place.lat, lng: place.lng, addressDetail: `Kiot ${n}, sảnh toà S3.01` },
      { openingHours: [1, 2, 3, 4, 5, 6, 7].map((weekday) => ({ weekday, opensAt: '09:00', closesAt: '21:00' })), deliveryRadiusKm: 2, deliveryFee: 5000, freeDeliveryThreshold: 100000 },
      { businessType: 'INDIVIDUAL', businessAddress: `Kiot ${n}, ${AREA}`, taxCode: '0100000000', invoiceEmails: [email], bankName: 'Vietcombank', accountNumber: '0000000000', accountHolderName: sellerName },
      { docType: 'CCCD', docNumber: `0011950${phone.slice(-5)}`, fullName: sellerName, accuracyConfirmed: true, sellerTermsDocumentId: terms, privacyPolicyDocumentId: privacy },
    ]
    const upto = end === 'DRAFT' ? 2 : 4
    let failed = false
    for (const [i, body] of steps.slice(0, upto).entries()) {
      if (failed) break
      await call('PUT', `/api/merchant/shop/steps/${i + 1}`, { body, token }).catch((e) => { failed = true; soft(`step ${i + 1} ${shopName}`)(e) })
    }
    if (failed || end === 'DRAFT') continue
    for (const kind of ['identity-front', 'identity-selfie']) await call('POST', `/api/merchant/shop/files/${kind}`, { token, form: png() }).catch(soft('id photo'))
    const submitted = await call('POST', '/api/merchant/shop/submit', { token }).catch(soft(`submit ${shopName}`))
    if (submitted && end === 'REJECTED') {
      const row = items(await call('GET', `/api/admin/merchant-approval/requests?status=PENDING&q=${encodeURIComponent(shopName)}`, { token: admin })).find((r) => r.shop.name === shopName)
      if (row) await call('POST', `/api/admin/merchant-approval/requests/${row.shop.vendorId}/reject`, { token: admin, body: { reason: 'Ảnh giấy tờ bị mờ, không đọc được số. Vui lòng chụp lại rõ nét.' } }).catch(soft('reject'))
    }
  }
  log('  + shop applications (3 waiting, 1 rejected, 1 draft)')
}

/** Words the chat filter hides. */
async function moderation(admin) {
  const have = items(await call('GET', '/api/admin/moderation-terms', { token: admin })).map((t) => t.term)
  for (const term of ['lừa đảo', 'chuyển khoản riêng', 'zalo riêng', 'đồ ngu']) if (!have.includes(term)) await call('POST', '/api/admin/moderation-terms', { token: admin, body: { term } }).catch(soft('term'))
  log('  + moderation terms')
}

// ---------------------------------------------------------------- main

async function main() {
  if (!ADMIN_EMAIL || !ADMIN_PASSWORD) throw new Error('Set ADMIN_EMAIL and ADMIN_PASSWORD.')
  log(`Bringing the demo to life on ${API}`)
  const admin = (await call('POST', '/api/admin/auth/login', { body: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD } })).accessToken
  const { shops, customers } = await people(admin)
  if (shops.length < 6 || customers.length < 4) throw new Error('Run seed.mjs first: the demo shops and customers are missing.')
  log(`${shops.length} shops, ${customers.length} customers`)
  log('No-shows (started)')
  const noShows = await startNoShows(customers, shops, admin).catch((e) => (soft('no-shows')(e), []))
  for (const [name, step] of [
    ['Customers', () => addressesAndTastes(customers, shops)],
    ['Shop tools', () => shopTools(shops)],
    ['Chats', () => chats(customers, shops)],
    ['Complaints', () => complaints(customers, shops, admin)],
    ['Support', () => support(customers, shops, admin)],
    ['Reports', () => reports(customers, shops, admin)],
    ['Personal data', () => dataRequests(customers, admin)],
    ['Help center', () => helpArticles(admin)],
    ['Performance and money', () => performanceAndMoney(shops, admin)],
    ['Dish categories', () => categoryCases(shops, admin)],
    ['Applications', () => applications(admin)],
    ['Moderation', () => moderation(admin)],
    ['No-shows (reported)', () => finishNoShows(noShows)],
  ]) {
    log(name)
    await step().catch(soft(name))
  }
  if (skipped.length) log(`\n${skipped.length} steps skipped:\n  ${skipped.slice(0, 25).join('\n  ')}`)
  log('Done.')
}

main().catch((e) => {
  console.error(e.message)
  process.exit(1)
})
