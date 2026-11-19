# Demo data

`seed.mjs` fills a running bonbon stack with a small, believable neighbourhood for demos, end-to-end tests and load
tests. It goes through the public API, so every order posts its ledger entries, notifications and statistics exactly
as real use would. Everything is invented: people, shops, phone numbers (`09000002xx`) and emails on `example.com`.

## What you get

| | |
|---|---|
| Shops (approved, accepting orders) | 20, across every leaf category: the original six (Cơm nhà Bếp Sen, Bún chả Phố Hàng, Phở gà Lan Chi, Bánh mì Nắng, Trà chanh Góc Sân, Chè & sữa chua Mây) and 14 more in `neighbourhood.mjs` (bún bò, gà rán, lẩu nướng, bánh cuốn, cà phê, tiệm bánh, mì Quảng, cơm văn phòng, pizza, sinh tố, bánh xèo, hủ tiếu, trà sữa, bún đậu); about 120 dishes with sections, option groups (khẩu phần, thêm, mức đường) and a couple sold out |
| Sellers | `demo.seller1@example.com` … `demo.seller20@example.com` (one shop each, in the order above) |
| Customers | `demo.khach1@example.com` … `demo.khach16@example.com`, each with a default address |
| Orders, first scenarios | 15 delivered (7 reviewed, 3 with a shop reply), 2 placed, 1 confirmed, 1 preparing, 1 out for delivery, 1 cancelled by the customer, 1 rejected by the shop, 1 delivered order with an open "missing item" case |
| Orders, busy wave | about 80 more across all shops (popular shops get more): most delivered, about 50 reviews from 2 to 5 stars, replies on the low ones, a few cancelled or rejected, new and cooking orders on five of the newer shops; favourite shops for four customers |
| Money | Commission ledger entries for every delivered order and a recorded payout to Cơm nhà Bếp Sen |
| Pictures (`photos.mjs`) | a logo, a cover and a photo for every dish, and ten home page categories with pictures |

Password for every demo account: `bonbon-demo-2027` (override with `DEMO_PASSWORD`). The admin is the one from the
backend's admin seed (`ADMIN_SEED_EMAIL` / `ADMIN_SEED_PASSWORD`).

## Run it

Needs Node 18+, the backend, and Mailpit (the script reads the verification emails from it). From `backend/`, in Git Bash:

```bash
ADMIN_EMAIL=... ADMIN_PASSWORD=... DEMO_REDIS_CONTAINER=bonbon-dev-redis-1 node demo-data/seed.mjs
```

- `--always-open` sets every shop to 00:00–23:59 so orders can be placed at any hour (end-to-end tests use it).
  Without it, shops get realistic opening hours; orders are only placed while the shops are open, so run the first
  seed with `--always-open` and then run again without it to restore realistic hours.
- `DEMO_REDIS_CONTAINER`: the API allows 5 registrations per IP per hour and the seed registers 36 accounts. With this
  set, the script clears that counter in the stack's own Redis (through `docker exec`) instead of waiting. Never point
  it at production.
- `BONBON_API` (default `http://localhost:8080`), `BONBON_MAILPIT` (default `http://localhost:8025`) and `DEMO_AREA`
  (the place query every address is built from, default `Vinhomes Smart City Tây Mỗ`) point it at another stack.

Running it again is safe: accounts, shops, menu items and addresses are created only when missing, the first order
scenarios run only while the first demo customer has no orders, and the busy wave only while none of customers 5 to 16 has
one. On a stack seeded before the busy wave existed, running it again adds the new shops, customers and the busy wave.

Then the pictures, from a folder of food photos sorted by kind (the Food-11 layout: Bread, Rice, Noodles-Pasta, ...):

```bash
PHOTOS_DIR=~/Downloads/archive/evaluation ADMIN_EMAIL=... ADMIN_PASSWORD=... node demo-data/photos.mjs
```

### On the demo server

Mailpit listens only on the server, so open a tunnel first and point the seed at the public API:

```bash
ssh -L 8025:127.0.0.1:8025 <user>@<demo server>      # keep it open in another terminal
BONBON_API=https://api.bonbon.pro.vn ADMIN_EMAIL=... ADMIN_PASSWORD=... node demo-data/seed.mjs --always-open
BONBON_API=https://api.bonbon.pro.vn ADMIN_EMAIL=... ADMIN_PASSWORD=... node demo-data/seed.mjs        # real opening hours again
BONBON_API=https://api.bonbon.pro.vn PHOTOS_DIR=... ADMIN_EMAIL=... ADMIN_PASSWORD=... node demo-data/photos.mjs
```

Without `DEMO_REDIS_CONTAINER` (the server's Redis is not on this machine) the seed waits out the registration limit, about
an hour per five accounts; run it on the server itself with `DEMO_REDIS_CONTAINER` set to skip the wait.

## Start over

```bash
docker compose -f ../deploy/compose.dev.yaml down -v   # deletes the database volume
docker compose -f ../deploy/compose.dev.yaml --env-file ../deploy/.env up -d
# start the backend (Flyway recreates the schema), then seed again
```

## Limits

- Orders are placed "now", so revenue charts show the demo orders on the day the seed ran.
- Payments are cash on delivery; MoMo needs real sandbox credentials.
