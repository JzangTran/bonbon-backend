#!/usr/bin/env node
// Pictures for the demo data made by seed.mjs: a photo for every dish, a logo and a cover for every shop, and the home page's
// display categories (created when missing) with their pictures. Pictures come from a local folder of food photos sorted in
// sub-folders by kind (the Food-11 layout: Bread, Rice, Noodles-Pasta, ...); the same dish always gets the same picture, so
// running it again changes nothing that already has one.
//
// Usage (Git Bash):
//   PHOTOS_DIR=~/Downloads/archive/evaluation ADMIN_EMAIL=... ADMIN_PASSWORD=... node demo-data/photos.mjs
// Env: BONBON_API (default http://localhost:8080), DEMO_PASSWORD (default bonbon-demo-2027), PHOTOS_DIR (required).
// Without ADMIN_EMAIL/ADMIN_PASSWORD the display categories are skipped.

import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { createHash } from 'node:crypto'

const API = (process.env.BONBON_API ?? 'http://localhost:8080').replace(/\/+$/, '')
const PASSWORD = process.env.DEMO_PASSWORD ?? 'bonbon-demo-2027'
const DIR = process.env.PHOTOS_DIR
const ADMIN_EMAIL = process.env.ADMIN_EMAIL
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD
if (!DIR) throw new Error('Set PHOTOS_DIR to a folder of food photos sorted by kind')

const SELLERS = Array.from({ length: 20 }, (_, i) => `demo.seller${i + 1}@example.com`)

/** Words in a dish name, first match wins: "Cơm sườn" is rice, "Bún chả" is noodles. */
const KINDS = [
  [/bánh mì|croissant|bánh mì bơ tỏi/i, 'Bread'],
  [/pizza/i, 'Bread'],
  [/cơm|xôi/i, 'Rice'],
  [/bún|phở|mì|mỳ|miến|hủ tiếu|nui|bánh cuốn|hoành thánh/i, 'Noodles-Pasta'],
  [/trứng|ốp la/i, 'Egg'],
  [/canh|súp|lẩu/i, 'Soup'],
  [/sữa chua|sữa/i, 'Dairy product'],
  [/chè|kem|flan|tráng miệng|tiramisu|su kem|bông lan|bánh ngọt/i, 'Dessert'],
  [/trà|nước|sinh tố|ép|chanh|cà phê/i, 'Vegetable-Fruit'],
  [/cá|tôm|mực|hải sản|cua/i, 'Seafood'],
  [/nem|chả|chiên|rán|khoai|bánh xèo|bánh khọt|popcorn/i, 'Fried food'],
  [/gà|sườn|thịt|bò|heo|nướng/i, 'Meat'],
  [/rau|đậu|salad|nộm|trái cây|gỏi|bò bía/i, 'Vegetable-Fruit'],
]
const kindOf = (name, fallback = 'Meat') => KINDS.find(([re]) => re.test(name))?.[1] ?? fallback

/** The home page's display categories and the leaf categories (seed.mjs) each gathers. */
const DISPLAY = [
  ['Cơm', ['Cơm'], 'Rice'],
  ['Bún phở', ['Bún & phở'], 'Noodles-Pasta'],
  ['Bánh mì', ['Bánh mì & xôi'], 'Bread'],
  ['Trà sữa', ['Trà sữa', 'Nước ép & sinh tố'], 'Dairy product'],
  ['Ăn vặt', ['Chè & tráng miệng'], 'Dessert'],
  ['Mì & miến', ['Mì & miến'], 'Noodles-Pasta'],
  ['Gà rán & pizza', ['Đồ ăn vặt'], 'Fried food'],
  ['Lẩu nướng', ['Lẩu & nướng'], 'Meat'],
  ['Cà phê', ['Cà phê', 'Trà & nước giải khát'], 'Dairy product'],
  ['Bánh ngọt', ['Bánh ngọt'], 'Dessert'],
]

const files = new Map()
function pick(kind, key) {
  if (!files.has(kind)) {
    const dir = join(DIR, kind)
    files.set(kind, readdirSync(dir).filter((f) => /\.jpe?g$/i.test(f) && statSync(join(dir, f)).size < 1_500_000).sort())
  }
  const list = files.get(kind)
  if (!list.length) throw new Error(`No photos in ${join(DIR, kind)}`)
  const n = createHash('sha1').update(key).digest().readUInt32BE(0)
  return join(DIR, kind, list[n % list.length])
}

async function call(method, path, { token, body, file } = {}) {
  const init = { method, headers: {} }
  if (token) init.headers.Authorization = `Bearer ${token}`
  if (file) {
    const form = new FormData()
    form.append('file', new Blob([readFileSync(file)], { type: 'image/jpeg' }), 'photo.jpg')
    init.body = form
  } else if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }
  for (let attempt = 0; ; attempt++) {
    const res = await fetch(API + path, init)
    if (res.status === 429 && attempt < 10) {
      await new Promise((r) => setTimeout(r, (Number(res.headers.get('Retry-After')) || 10) * 1000))
      continue
    }
    const text = await res.text()
    if (!res.ok) throw new Error(`${method} ${path} → ${res.status} ${text.slice(0, 200)}`)
    return text ? JSON.parse(text) : null
  }
}

async function login(email, password) {
  let data = await call('POST', '/api/auth/login', { body: { email, password } })
  if (data.needsRoleSelection) data = await call('POST', '/api/auth/select-role', { body: { roleToken: data.roleToken, role: 'SELLER' } })
  return data.tokens.accessToken
}

let added = 0
for (const email of SELLERS) {
  let token
  try {
    token = await login(email, PASSWORD)
  } catch (e) {
    console.log(`  - ${email}: cannot sign in (${e.message.slice(0, 80)}), skipped`)
    continue
  }
  const shop = (await call('GET', '/api/merchant/shop', { token })).shop ?? {}
  const menu = await call('GET', '/api/merchant/menu', { token })
  const items = (menu.sections ?? []).flatMap((s) => s.items ?? [])
  const main = kindOf(`${shop.name ?? ''} ${items[0]?.name ?? ''}`)
  if (!shop.logoUrl) {
    await call('PUT', '/api/merchant/shop/logo', { token, file: pick(main, `logo:${shop.name}`) })
    added++
  }
  if (!shop.coverUrl) {
    await call('PUT', '/api/merchant/shop/cover', { token, file: pick(main, `cover:${shop.name}`) })
    added++
  }
  for (const item of items) {
    if (item.photoUrl || item.imageUrl) continue
    await call('PUT', `/api/merchant/menu-items/${item.id}/photo`, { token, file: pick(kindOf(item.name, main), `dish:${shop.name}:${item.name}`) })
    added++
  }
  console.log(`  + ${shop.name}: ${items.length} dishes`)
}

if (ADMIN_EMAIL && ADMIN_PASSWORD) {
  const admin = (await call('POST', '/api/admin/auth/login', { body: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD } })).accessToken
  const leaves = []
  const walk = (nodes) => nodes.forEach((n) => (n.children?.length ? walk(n.children) : leaves.push(n)))
  walk(await call('GET', '/api/categories'))
  const existing = await call('GET', '/api/admin/display-categories', { token: admin })
  for (const [name, leafNames, kind] of DISPLAY) {
    const leafIds = leaves.filter((l) => leafNames.includes(l.name)).map((l) => l.id)
    if (!leafIds.length) continue
    let category = existing.find((c) => c.name === name)
    if (!category) category = await call('POST', '/api/admin/display-categories', { token: admin, body: { name, leafIds } })
    if (!category.imageUrl) {
      await call('PUT', `/api/admin/display-categories/${category.id}/image`, { token: admin, file: pick(kind, `display:${name}`) })
      added++
    }
  }
  console.log(`  + display categories: ${DISPLAY.map(([n]) => n).join(', ')}`)
} else {
  console.log('  - display categories skipped (no ADMIN_EMAIL / ADMIN_PASSWORD)')
}
console.log(`Done: ${added} pictures added.`)
