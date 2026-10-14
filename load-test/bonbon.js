// k6 load test for the busiest paths: shops in the area (Haversine filter), dish/shop search, a shop's menu, and
// order placement. Needs the demo data (demo-data/seed.mjs --always-open). Run with Docker (Git Bash):
//   docker run --rm -i -e API=http://host.docker.internal:8080 grafana/k6 run - < load-test/bonbon.js
// Env: API, VUS (browsing users, default 50), DURATION (default 2m), ORDER_RATE (orders per second, default 2),
//      DEMO_PASSWORD (default bonbon-demo-2027).

import http from 'k6/http'
import { check, fail } from 'k6'

const API = __ENV.API || 'http://host.docker.internal:8080'
const VUS = Number(__ENV.VUS || 50)
const DURATION = __ENV.DURATION || '2m'
const ORDER_RATE = Number(__ENV.ORDER_RATE || 2)
const PASSWORD = __ENV.DEMO_PASSWORD || 'bonbon-demo-2027'
const CUSTOMERS = ['demo.khach1@example.com', 'demo.khach2@example.com', 'demo.khach3@example.com', 'demo.khach4@example.com']
const QUERIES = ['bún', 'phở', 'cơm', 'trà', 'bánh mì', 'chè', 'xôi', 'sinh tố']
const JSON_HEADERS = { 'Content-Type': 'application/json' }

export const options = {
  scenarios: {
    browse: { executor: 'constant-vus', exec: 'browse', vus: VUS, duration: DURATION },
    order: {
      executor: 'constant-arrival-rate', exec: 'order', rate: ORDER_RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 10, maxVUs: 40,
    },
  },
  thresholds: {
    'http_req_failed{scenario:browse}': ['rate<0.01'],
    'http_req_failed{scenario:order}': ['rate<0.01'],
    'http_req_duration{name:area}': ['p(95)<300'],
    'http_req_duration{name:search}': ['p(95)<400'],
    'http_req_duration{name:menu}': ['p(95)<300'],
    'http_req_duration{name:place}': ['p(95)<800'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
}

function login(email) {
  const res = http.post(`${API}/api/auth/login`, JSON.stringify({ email, password: PASSWORD }), { headers: JSON_HEADERS })
  if (res.status !== 200) fail(`login ${email}: ${res.status} ${res.body}`)
  return res.json('tokens.accessToken')
}

/** Signs the demo customers in once and reads where they live and what the shops sell. */
export function setup() {
  const customers = CUSTOMERS.map((email) => {
    const token = login(email)
    const auth = { headers: { Authorization: `Bearer ${token}` } }
    const address = http.get(`${API}/api/account/addresses`, auth).json()[0]
    return { token, addressId: address.id, lat: address.lat, lng: address.lng }
  })
  const { lat, lng } = customers[0]
  const shops = http.get(`${API}/api/vendors?lat=${lat}&lng=${lng}&size=50`).json('items')
  if (!shops.length) fail('No shops in the demo area: seed the demo data first.')
  const menus = shops.map((shop) => {
    const menu = http.get(`${API}/api/vendors/${shop.id}/menu?lat=${lat}&lng=${lng}`).json()
    const dishes = menu.sections.flatMap((s) => s.items).filter((i) => !i.soldOut)
    return {
      vendorId: shop.id,
      dishes: dishes.map((d) => ({
        id: d.id,
        optionIds: (d.optionGroups || []).filter((g) => g.min > 0).map((g) => (g.options.find((o) => o.defaultChoice) || g.options[0]).id),
      })),
    }
  })
  return { customers, shops: shops.map((s) => s.id), menus, lat, lng }
}

const pick = (list) => list[Math.floor(Math.random() * list.length)]

/** A guest or customer looking around: the area list, a search, a shop's menu. */
export function browse(data) {
  const { lat, lng } = data
  check(http.get(`${API}/api/vendors?lat=${lat}&lng=${lng}`, { tags: { name: 'area' } }), { 'area 200': (r) => r.status === 200 })
  const q = encodeURIComponent(pick(QUERIES))
  check(http.get(`${API}/api/vendors?lat=${lat}&lng=${lng}&q=${q}`, { tags: { name: 'search' } }), { 'search 200': (r) => r.status === 200 })
  check(http.get(`${API}/api/vendors/${pick(data.shops)}/menu?lat=${lat}&lng=${lng}`, { tags: { name: 'menu' } }), { 'menu 200': (r) => r.status === 200 })
}

/** A cash order placed and then cancelled by the customer, so open-order limits never fill up. */
export function order(data) {
  const customer = data.customers[__ITER % data.customers.length]
  const menu = pick(data.menus)
  const dish = pick(menu.dishes)
  const headers = { ...JSON_HEADERS, Authorization: `Bearer ${customer.token}`, 'Idempotency-Key': `k6-${__VU}-${__ITER}-${Date.now()}` }
  const body = { vendorId: menu.vendorId, addressId: customer.addressId, paymentMethod: 'COD', items: [{ menuItemId: dish.id, quantity: 1, optionIds: dish.optionIds }] }
  const placed = http.post(`${API}/api/orders`, JSON.stringify(body), { headers, tags: { name: 'place' } })
  if (!check(placed, { 'place 201': (r) => r.status === 201 })) return
  const cancel = http.post(`${API}/api/orders/${placed.json('id')}/cancel`, JSON.stringify({ reason: 'k6' }),
    { headers: { ...JSON_HEADERS, Authorization: `Bearer ${customer.token}` }, tags: { name: 'cancel' } })
  check(cancel, { 'cancel 200': (r) => r.status === 200 })
}
