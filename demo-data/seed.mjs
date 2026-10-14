#!/usr/bin/env node
// Demo data for bonbon: a small neighbourhood with shops, menus, customers and orders in every status.
// It drives the public API (the same rules, events and ledger postings as real use), so it works against
// any running stack that also runs Mailpit (to read the verification emails). Safe to run again: every step
// checks what already exists. All people, shops and contacts are invented (emails on example.com).
//
// Usage (Git Bash, backend and Mailpit running):
//   ADMIN_EMAIL=... ADMIN_PASSWORD=... node demo-data/seed.mjs [--always-open]
// Env: BONBON_API (default http://localhost:8080), BONBON_MAILPIT (default http://localhost:8025),
//      DEMO_PASSWORD (default bonbon-demo-2027), DEMO_AREA (place query, default "Vinhomes Smart City Tây Mỗ"),
//      DEMO_REDIS_CONTAINER (e.g. bonbon-dev-redis-1): the seed registers 10 accounts, more than the 5 per IP per hour
//      the API allows; with this set it clears that counter in the stack's own Redis instead of waiting an hour.

import { execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'

const API = (process.env.BONBON_API ?? 'http://localhost:8080').replace(/\/+$/, '')
const MAILPIT = (process.env.BONBON_MAILPIT ?? 'http://localhost:8025').replace(/\/+$/, '')
const PASSWORD = process.env.DEMO_PASSWORD ?? 'bonbon-demo-2027'
const AREA = process.env.DEMO_AREA ?? 'Vinhomes Smart City Tây Mỗ'
const ADMIN_EMAIL = process.env.ADMIN_EMAIL
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD
const ALWAYS_OPEN = process.argv.includes('--always-open')
const REDIS_CONTAINER = process.env.DEMO_REDIS_CONTAINER

// A 1×1 PNG, enough for identity documents and case photos in a demo.
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  'base64',
)

// ---------------------------------------------------------------- data (invented)

const SHOPS = [
  {
    seller: { email: 'demo.seller1@example.com', name: 'Trần Thị Sen' },
    name: 'Cơm nhà Bếp Sen', place: `Toà S1.01 ${AREA}`, detail: 'Kiot 02, sảnh toà S1.01', phone: '0900000101',
    radiusKm: 3, fee: 5000, freeFrom: 150000, hours: [['06:30', '13:30'], ['16:30', '20:30']],
    menu: [
      { section: 'Cơm phần', category: 'Cơm', items: [
        ['Cơm sườn nướng', 'Sườn cốt lết nướng than, canh rau theo ngày.', 45000, 'size'],
        ['Cơm gà luộc lá chanh', 'Gà ta luộc, xôi gấc nhỏ, nước chấm gừng.', 50000, 'size'],
        ['Cơm cá kho tộ', 'Cá basa kho tộ, rau luộc, trứng kho.', 45000, 'size'],
        ['Cơm thịt kho trứng', 'Thịt ba chỉ kho nước dừa, trứng vịt.', 42000, 'size'],
      ] },
      { section: 'Canh và món thêm', category: 'Cơm', items: [
        ['Canh chua cá', 'Một bát canh chua cá lóc.', 20000],
        ['Trứng ốp la', 'Một quả.', 8000],
        ['Đậu phụ sốt cà chua', 'Một đĩa nhỏ.', 15000, null, 'soldOut'],
      ] },
    ],
  },
  {
    seller: { email: 'demo.seller2@example.com', name: 'Lê Văn Hàng' },
    name: 'Bún chả Phố Hàng', place: `Toà S2.03 ${AREA}`, detail: 'Shophouse SH-07', phone: '0900000102',
    radiusKm: 3, fee: 8000, freeFrom: 200000, hours: [['10:00', '14:00'], ['17:00', '21:00']],
    menu: [
      { section: 'Bún chả', category: 'Bún & phở', items: [
        ['Bún chả suất thường', 'Chả viên và chả miếng nướng than, bún, rau sống.', 45000, 'extra'],
        ['Bún chả suất đặc biệt', 'Thêm chả, thêm nem cua bể.', 65000, 'extra'],
        ['Nem cua bể (2 chiếc)', 'Chiên giòn, kèm nước chấm.', 30000],
      ] },
      { section: 'Đồ uống', category: 'Nước ép & sinh tố', items: [
        ['Trà đá', '', 5000],
        ['Nước sấu đá', 'Sấu ngâm đường nhà làm.', 15000],
      ] },
    ],
  },
  {
    seller: { email: 'demo.seller3@example.com', name: 'Nguyễn Thị Chi' },
    name: 'Phở gà Lan Chi', place: `Toà S3.02 ${AREA}`, detail: 'Tầng 1, cạnh cửa thang máy B', phone: '0900000103',
    radiusKm: 2.5, fee: 6000, freeFrom: null, hours: [['06:00', '10:30'], ['17:30', '21:30']],
    menu: [
      { section: 'Phở và miến', category: 'Bún & phở', items: [
        ['Phở gà ta', 'Gà ta xé, nước dùng ninh xương.', 50000, 'size'],
        ['Miến gà', 'Miến dong, lòng gà.', 45000, 'size'],
        ['Phở gà trộn', 'Phở trộn sốt chua ngọt, lạc rang.', 55000],
      ] },
      { section: 'Món thêm', category: 'Bún & phở', items: [
        ['Quẩy (2 chiếc)', '', 6000],
        ['Trứng gà non', 'Một phần nhỏ.', 15000],
      ] },
    ],
  },
  {
    seller: { email: 'demo.seller4@example.com', name: 'Phạm Minh Nắng' },
    name: 'Bánh mì Nắng', place: `Toà S1.05 ${AREA}`, detail: 'Xe bánh mì trước sảnh S1.05', phone: '0900000104',
    radiusKm: 2, fee: 5000, freeFrom: 80000, hours: [['06:00', '11:00'], ['15:00', '19:00']],
    menu: [
      { section: 'Bánh mì', category: 'Bánh mì & xôi', items: [
        ['Bánh mì pate trứng', 'Pate gan nhà làm, trứng ốp.', 25000, 'extra'],
        ['Bánh mì thịt nướng', 'Thịt nướng sả, đồ chua.', 30000, 'extra'],
        ['Bánh mì chảo', 'Trứng, xúc xích, pate, bánh mì giòn.', 45000],
      ] },
      { section: 'Xôi', category: 'Bánh mì & xôi', items: [
        ['Xôi xéo', 'Đỗ xanh, hành phi, mỡ hành.', 20000],
        ['Xôi gà', 'Gà xé, ruốc, hành phi.', 35000],
      ] },
    ],
  },
  {
    seller: { email: 'demo.seller5@example.com', name: 'Đỗ Thu Trang' },
    name: 'Trà chanh Góc Sân', place: `Toà S2.05 ${AREA}`, detail: 'Ki-ốt sân chơi trung tâm', phone: '0900000105',
    radiusKm: 3, fee: 3000, freeFrom: 60000, hours: [['08:00', '22:00']],
    menu: [
      { section: 'Trà', category: 'Trà sữa', items: [
        ['Trà chanh giã tay', 'Chanh tươi giã, trà nhài.', 20000, 'sugar'],
        ['Trà quất mật ong', '', 25000, 'sugar'],
        ['Trà sữa nướng', 'Trà rang, sữa tươi, trân châu đen.', 35000, 'sugar'],
      ] },
      { section: 'Sinh tố và nước ép', category: 'Nước ép & sinh tố', items: [
        ['Sinh tố bơ', 'Bơ sáp, sữa đặc.', 40000],
        ['Nước ép cam', 'Cam sành vắt.', 30000],
      ] },
    ],
  },
  {
    seller: { email: 'demo.seller6@example.com', name: 'Vũ Ngọc Mây' },
    name: 'Chè & sữa chua Mây', place: `Toà S3.03 ${AREA}`, detail: 'Tầng 1, ki-ốt 05', phone: '0900000106',
    radiusKm: 3, fee: 4000, freeFrom: 100000, hours: [['13:00', '22:30']],
    menu: [
      { section: 'Chè', category: 'Chè & tráng miệng', items: [
        ['Chè khúc bạch', 'Khúc bạch hạnh nhân, vải, nước đường phèn.', 30000, 'extra'],
        ['Chè thập cẩm', 'Đỗ đen, đỗ xanh, thạch, cốt dừa.', 25000],
        ['Chè bưởi', '', 25000],
      ] },
      { section: 'Sữa chua', category: 'Chè & tráng miệng', items: [
        ['Sữa chua nếp cẩm', 'Sữa chua nhà làm.', 22000],
        ['Sữa chua mít', '', 28000],
      ] },
    ],
  },
]

const OPTION_GROUPS = {
  size: { name: 'Khẩu phần', min: 1, max: 1, options: [['Suất thường', 0, true], ['Suất lớn', 10000]] },
  extra: { name: 'Thêm', min: 0, max: 3, options: [['Thêm thịt', 15000], ['Thêm rau', 0], ['Thêm trứng', 8000]] },
  sugar: { name: 'Mức đường', min: 1, max: 1, options: [['Bình thường', 0, true], ['Ít đường', 0], ['Không đường', 0]] },
}

const CUSTOMERS = [
  { email: 'demo.khach1@example.com', name: 'Hoàng Minh Anh', phone: '0900000201', place: `Toà S1.02 ${AREA}`, detail: 'Căn 1208' },
  { email: 'demo.khach2@example.com', name: 'Bùi Quang Huy', phone: '0900000202', place: `Toà S2.01 ${AREA}`, detail: 'Căn 0715' },
  { email: 'demo.khach3@example.com', name: 'Đặng Thu Hà', phone: '0900000203', place: `Toà S3.01 ${AREA}`, detail: 'Căn 2103' },
  { email: 'demo.khach4@example.com', name: 'Ngô Đức Long', phone: '0900000204', place: `Toà S1.03 ${AREA}`, detail: 'Căn 0902' },
]

const REVIEWS = [
  [5, 'Cơm nóng, đóng hộp cẩn thận, giao nhanh.'],
  [5, 'Quán giao tận cửa, vẫn còn nóng.'],
  [4, 'Ngon, hơi nhiều nước mắm một chút.'],
  [5, 'Lần thứ ba đặt, vẫn ổn định.'],
  [4, 'Đồ ăn ngon, lần sau cho thêm rau nhé.'],
  [3, 'Giao hơi lâu giờ cao điểm.'],
  [5, ''],
]
const REPLIES = ['Cảm ơn bạn đã ủng hộ quán!', 'Quán ghi nhận, lần sau sẽ cho thêm rau ạ.', 'Xin lỗi bạn, giờ trưa quán hơi đông, quán sẽ cải thiện.']

// ---------------------------------------------------------------- http

class ApiError extends Error {
  constructor(status, body, where) {
    super(`${where} -> ${status} ${body?.code ?? ''} ${body?.detail ?? JSON.stringify(body)}`)
    this.status = status
    this.body = body
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
  // Registration and login are rate limited per client; reset the local counter or wait as told, then retry.
  for (let retry = 0; res.status === 429 && retry < 12; retry++) {
    if (path === '/api/auth/register' && REDIS_CONTAINER) {
      resetRegisterLimit()
    } else {
      const wait = Number(res.headers.get('Retry-After')) || 10
      log(`  … rate limited on ${path}, waiting ${wait}s`)
      await new Promise((r) => setTimeout(r, wait * 1000))
    }
    if (form) init.body = form
    res = await fetch(API + path, init)
  }
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  if (!res.ok) throw new ApiError(res.status, data, `${method} ${path}`)
  return data
}

/** Deletes the per-IP registration counters in the stack's Redis (local/demo stacks only, never production). */
function resetRegisterLimit() {
  const keys = execFileSync('docker', ['exec', REDIS_CONTAINER, 'redis-cli', '--scan', '--pattern', 'register_count:ip:*'], { encoding: 'utf8' })
    .split(/\s+/).filter(Boolean)
  if (keys.length) execFileSync('docker', ['exec', REDIS_CONTAINER, 'redis-cli', 'del', ...keys])
}

function pngForm() {
  const form = new FormData()
  form.append('file', new Blob([PNG], { type: 'image/png' }), 'demo.png')
  return form
}

const log = (...args) => console.log(...args)

// ---------------------------------------------------------------- accounts

async function legalIds(...types) {
  return Promise.all(types.map(async (t) => (await call('GET', `/api/legal/documents/${t}`)).id))
}

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
  throw new Error(`No verification email for ${email} in Mailpit (${MAILPIT})`)
}

async function login(email, role) {
  let data = await call('POST', '/api/auth/login', { body: { email, password: PASSWORD } })
  if (data.needsRoleSelection) data = await call('POST', '/api/auth/select-role', { body: { roleToken: data.roleToken, role } })
  return data.tokens.accessToken
}

/** Signs in, or registers and verifies the account first when it doesn't exist yet. */
async function account({ email, name }, role) {
  try {
    return await login(email, role)
  } catch (e) {
    if (!(e instanceof ApiError) || (e.status !== 401 && e.status !== 403)) throw e
  }
  const docs = role === 'SELLER' ? await legalIds('SELLER_TERMS', 'PRIVACY_POLICY') : await legalIds('CUSTOMER_TERMS', 'PRIVACY_POLICY')
  try {
    await call('POST', '/api/auth/register', { body: { email, password: PASSWORD, name, role, acceptedDocumentIds: docs } })
  } catch (e) {
    if (!(e instanceof ApiError) || e.status !== 409) throw e // already registered but not verified yet
    await call('POST', '/api/auth/resend-verification', { body: { email } }).catch(() => {})
  }
  await call('GET', `/api/auth/verify-email?token=${await verificationToken(email)}`)
  log(`  + ${role.toLowerCase()} ${email}`)
  return login(email, role)
}

async function adminToken() {
  if (!ADMIN_EMAIL || !ADMIN_PASSWORD) throw new Error('Set ADMIN_EMAIL and ADMIN_PASSWORD (the admin seed from the backend env).')
  return (await call('POST', '/api/admin/auth/login', { body: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD } })).accessToken
}

async function placeId(query, token) {
  const suggestions = await call('GET', `/api/geo/autocomplete?input=${encodeURIComponent(query)}`, { token })
  if (!suggestions.length) throw new Error(`No address suggestion for "${query}"`)
  return suggestions[0].placeId
}

// ---------------------------------------------------------------- shops

function leafCategories(tree) {
  const out = []
  const walk = (nodes) => nodes.forEach((n) => (n.children?.length ? walk(n.children) : out.push(n)))
  walk(tree)
  return out
}

function hoursOf(shop) {
  const windows = ALWAYS_OPEN ? [['00:00', '23:59']] : shop.hours
  return [1, 2, 3, 4, 5, 6, 7].flatMap((weekday) => windows.map(([opensAt, closesAt]) => ({ weekday, opensAt, closesAt })))
}

async function ensureShop(shop, admin, categories) {
  const token = await account(shop.seller, 'SELLER')
  let current = await call('GET', '/api/merchant/shop', { token }).catch((e) => (e.status === 404 ? null : Promise.reject(e)))

  if (!current || current.status === 'NONE' || current.status === 'DRAFT') {
    const [terms, privacy] = await legalIds('SELLER_TERMS', 'PRIVACY_POLICY')
    const steps = [
      { name: shop.name, phone: shop.phone, email: shop.seller.email, placeId: await placeId(shop.place, token), addressDetail: shop.detail },
      { openingHours: hoursOf(shop), deliveryRadiusKm: shop.radiusKm, deliveryFee: shop.fee, freeDeliveryThreshold: shop.freeFrom },
      { businessType: 'INDIVIDUAL', businessAddress: `${shop.detail}, ${AREA}`, taxCode: '0100000000',
        invoiceEmails: [shop.seller.email], bankName: 'Vietcombank', accountNumber: '0000000000', accountHolderName: shop.seller.name },
      { docType: 'CCCD', docNumber: '000000000000', fullName: shop.seller.name, accuracyConfirmed: true,
        sellerTermsDocumentId: terms, privacyPolicyDocumentId: privacy },
    ]
    for (const [i, body] of steps.entries()) await call('PUT', `/api/merchant/shop/steps/${i + 1}`, { body, token })
    for (const kind of ['identity-front', 'identity-selfie']) await call('POST', `/api/merchant/shop/files/${kind}`, { token, form: pngForm() })
    await call('POST', '/api/merchant/shop/submit', { token })
    current = await call('GET', '/api/merchant/shop', { token })
  }
  const id = await vendorIdOf(shop.name, current.status, admin)
  if (current.status === 'PENDING') {
    await call('POST', `/api/admin/merchant-approval/requests/${id}/approve`, { token: admin })
    log(`  + shop ${shop.name} approved`)
  }
  await call('PUT', '/api/merchant/shop/opening-hours', { token, body: { openingHours: hoursOf(shop) } })
  await ensureMenu(shop, id, token, categories)
  await call('PUT', '/api/merchant/shop/accepting-orders', { token, body: { accepting: true } })
  return { ...shop, id, token }
}

/** The seller's own shop view carries no id; the admin review queue does. */
async function vendorIdOf(name, status, admin) {
  const rows = await call('GET', `/api/admin/merchant-approval/requests?status=${status}&q=${encodeURIComponent(name)}`, { token: admin })
  const row = rows.find((r) => r.shop.name === name)
  if (!row) throw new Error(`Shop "${name}" (${status}) not found in the review queue`)
  return row.shop.vendorId
}

async function ensureMenu(shop, vendorId, token, categories) {
  const menu = await call('GET', `/api/vendors/${vendorId}/menu`, { token })
  const has = (section, item) => menu.sections.some((s) => s.name === section && (!item || s.items.some((i) => i.name === item)))
  if (shop.menu.every((sec) => sec.items.every(([name]) => has(sec.section, name)))) return

  const groups = {}
  const existing = await call('GET', '/api/merchant/option-groups', { token })
  for (const [key, g] of Object.entries(OPTION_GROUPS)) {
    const found = existing.groups.find((x) => x.name === g.name)
    if (found) {
      groups[key] = found.id
      continue
    }
    await call('POST', '/api/merchant/option-groups', { token, body: {
      name: g.name, min: g.min, max: g.max,
      options: g.options.map(([name, priceDelta, defaultChoice]) => ({ name, priceDelta, defaultChoice: !!defaultChoice })),
    } })
    groups[key] = (await call('GET', '/api/merchant/option-groups', { token })).groups.find((x) => x.name === g.name).id
  }

  for (const sec of shop.menu) {
    const section = menu.sections.find((s) => s.name === sec.section)
      ?? (await call('POST', '/api/merchant/menu-sections', { token, body: { name: sec.section } })).sections.find((s) => s.name === sec.section)
    const category = categories.find((c) => c.name === sec.category) ?? categories[0]
    for (const [name, description, price, group, flag] of sec.items) {
      if (has(sec.section, name)) continue
      const view = await call('POST', '/api/merchant/menu-items', { token, body: {
        sectionId: section.id, categoryId: category.id, name, description: description || undefined, price,
      } })
      const item = view.sections.flatMap((s) => s.items).find((i) => i.name === name)
      if (group) await call('PUT', `/api/merchant/menu-items/${item.id}/option-groups`, { token, body: { groupIds: [groups[group]] } })
      if (flag === 'soldOut') await call('PATCH', `/api/merchant/menu-items/${item.id}/status`, { token, body: { status: 'SOLD_OUT' } })
    }
  }
  log(`  + menu for ${shop.name}`)
}

// ---------------------------------------------------------------- customers and orders

async function ensureCustomer(c) {
  const token = await account(c, 'CUSTOMER')
  let addresses = await call('GET', '/api/account/addresses', { token })
  if (!addresses.length) {
    await call('POST', '/api/account/addresses', { token, body: {
      label: 'Nhà', placeId: await placeId(c.place, token), detail: c.detail, recipientName: c.name, recipientPhone: c.phone, makeDefault: true,
    } })
    addresses = await call('GET', '/api/account/addresses', { token })
  }
  return { ...c, token, addressId: addresses[0].id }
}

async function placeOrder(customer, shop, pick = 0) {
  const menu = await call('GET', `/api/vendors/${shop.id}/menu`, { token: customer.token })
  const items = menu.sections.flatMap((s) => s.items).filter((i) => !i.soldOut)
  const first = items[pick % items.length]
  const second = items[(pick + 2) % items.length]
  const lines = [first, second].filter(Boolean).map((i, n) => ({
    menuItemId: i.id,
    quantity: n === 0 ? 2 : 1,
    optionIds: (i.optionGroups ?? []).flatMap((g) => {
      const open = g.options.filter((o) => o.available !== false)
      return g.min > 0 ? [(open.find((o) => o.defaultChoice) ?? open[0]).id] : []
    }),
  }))
  return call('POST', '/api/orders', { token: customer.token, headers: { 'Idempotency-Key': randomUUID() }, body: {
    vendorId: shop.id, addressId: customer.addressId, paymentMethod: 'COD', items: lines,
    note: pick % 3 === 0 ? 'Gọi điện khi tới sảnh giúp mình.' : undefined,
  } })
}

const PATH = ['CONFIRMED', 'PREPARING', 'OUT_FOR_DELIVERY', 'DELIVERED']

async function advance(order, shop, target) {
  for (const step of PATH.slice(0, PATH.indexOf(target) + 1)) {
    if (step === 'CONFIRMED') await call('POST', `/api/merchant/orders/${order.id}/confirm`, { token: shop.token })
    else await call('POST', `/api/merchant/orders/${order.id}/status`, { token: shop.token, body: { to: step } })
  }
}

async function seedOrders(shops, customers, admin) {
  const existing = await call('GET', '/api/orders', { token: customers[0].token })
  if (existing.total > 0) {
    log('Orders already seeded; skipping the order scenarios.')
    return
  }
  let n = 0
  const order = async (ci, si, target) => {
    const o = await placeOrder(customers[ci], shops[si], n++)
    if (PATH.includes(target)) await advance(o, shops[si], target)
    return o
  }

  // Delivered history with reviews (and some shop replies).
  const delivered = []
  for (let i = 0; i < 14; i++) delivered.push({ o: await order(i % customers.length, i % shops.length, 'DELIVERED'), si: i % shops.length, ci: i % customers.length })
  for (const [i, d] of delivered.slice(0, REVIEWS.length).entries()) {
    const [rating, comment] = REVIEWS[i]
    await call('POST', `/api/orders/${d.o.id}/review`, { token: customers[d.ci].token, body: { rating, comment: comment || undefined } })
  }
  for (const [i, shop] of shops.slice(0, 3).entries()) {
    const reviews = await call('GET', '/api/merchant/reviews?unreplied=true', { token: shop.token })
    const first = reviews.items?.[0]
    if (first) await call('POST', `/api/merchant/reviews/${first.id}/response`, { token: shop.token, body: { text: REPLIES[i] } })
  }

  // One order in every live status, so each screen has something to show.
  await order(0, 0, 'PLACED')
  await order(1, 1, 'PLACED')
  await order(2, 2, 'CONFIRMED')
  await order(3, 3, 'PREPARING')
  await order(0, 4, 'OUT_FOR_DELIVERY')

  // Cancelled by the customer, rejected by the shop.
  const toCancel = await order(1, 5, 'PLACED')
  await call('POST', `/api/orders/${toCancel.id}/cancel`, { token: customers[1].token, body: { reason: 'Đặt nhầm món.' } })
  const toReject = await order(2, 1, 'PLACED')
  await call('POST', `/api/merchant/orders/${toReject.id}/reject`, { token: shops[1].token, body: { reason: 'Quán hết nguyên liệu cho món này.' } })

  // An open order case: a missing item on a delivered order.
  const caseOrder = await order(3, 0, 'DELIVERED')
  const detail = await call('GET', `/api/orders/${caseOrder.id}`, { token: customers[3].token })
  await call('POST', `/api/orders/${caseOrder.id}/incident`, { token: customers[3].token, body: {
    type: 'MISSING_ITEM', lines: [{ orderItemId: detail.items[0].id, quantity: 1 }], note: 'Thiếu một suất trong túi.',
  } })

  // A recorded payout to the first shop.
  const balance = await call('GET', `/api/admin/settlement/vendors/${shops[0].id}`, { token: admin })
  const owed = balance.payable
  if (owed > 0) {
    await call('POST', `/api/admin/settlement/vendors/${shops[0].id}/entries`, { token: admin, body: {
      type: 'PAYOUT', amount: Math.floor(owed / 2), reference: 'CK-DEMO-0001', note: 'Chi trả tuần (dữ liệu demo).',
    } })
  }
  log(`  + ${n} orders`)
}

// ---------------------------------------------------------------- main

async function main() {
  log(`Seeding demo data into ${API} (Mailpit ${MAILPIT})${ALWAYS_OPEN ? ', shops open all day' : ''}`)
  const admin = await adminToken()
  const categories = leafCategories(await call('GET', '/api/categories'))
  log('Shops')
  const shops = []
  for (const shop of SHOPS) shops.push(await ensureShop(shop, admin, categories))
  log('Customers')
  const customers = []
  for (const c of CUSTOMERS) customers.push(await ensureCustomer(c))
  log('Orders')
  await seedOrders(shops, customers, admin)
  log(`Done. Sign in with any demo.*@example.com account and the password "${PASSWORD}".`)
}

main().catch((e) => {
  console.error(e.message)
  process.exit(1)
})
