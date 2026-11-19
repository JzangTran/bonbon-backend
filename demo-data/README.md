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
| Everything else (`life.mjs`) | ~34 chat conversations (some unread) with quick replies and auto-replies; 1–3 vouchers per shop; work addresses, favourites, hidden shops, quiet hours and notification preferences; ~10 order complaints (waiting for the shop, accepted, disputed, decided by the admin) and 3 no-show reports; ~10 support tickets from customers and sellers (answered, waiting, closed) and public contact requests; reports of reviews, messages and shops (some decided); 5 personal-data requests at every stage; 14 help articles (2 drafts); penalties on four shops with appeals; payouts and adjustments; dishes flagged for the wrong category; 3 shop applications waiting for review, 1 rejected, 1 draft; chat moderation terms |
| Notifications | made by all of the above, as in real use (orders, chats, tickets, complaints, reviews) |
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

Then everything that is not a shop or an order (`life.mjs`), with the shops still open, and the realistic hours again after:

```bash
ADMIN_EMAIL=... ADMIN_PASSWORD=... DEMO_REDIS_CONTAINER=bonbon-dev-redis-1 node demo-data/life.mjs
ADMIN_EMAIL=... ADMIN_PASSWORD=... DEMO_REDIS_CONTAINER=bonbon-dev-redis-1 node demo-data/seed.mjs
```

`life.mjs` takes about 12 minutes: a shop may only report a no-show 10 minutes after the order left, so it starts those
orders first and reports them last. Each part checks what is there and runs once.

Then the pictures, from a folder of food photos sorted by kind (the Food-11 layout: Bread, Rice, Noodles-Pasta, ...):

```bash
PHOTOS_DIR=~/Downloads/archive/evaluation ADMIN_EMAIL=... ADMIN_PASSWORD=... node demo-data/photos.mjs
```

### On the demo server

The demo sends real email through an SMTP provider. While seeding, point the API back at the stack's own Mailpit, so the
verification emails can be read and the hundreds of notifications for `example.com` addresses do not go out. On the server,
in the deploy folder (the same `docker compose` command the deploy uses, with these variables in front of it):

```bash
SPRING_MAIL_HOST=mailpit SPRING_MAIL_PORT=1025 SPRING_MAIL_USERNAME= SPRING_MAIL_PASSWORD= BONBON_MAIL_SMTP_AUTH=false BONBON_MAIL_STARTTLS=false \
  docker compose -f compose.prod.yaml -f compose.demo.yaml --env-file .env.prod up -d backend
```

Then, from your computer, with a tunnel to that Mailpit open in another terminal (`ssh -L 8025:127.0.0.1:8025 <user>@<server>`):

```bash
export BONBON_API=https://api.bonbon.pro.vn ADMIN_EMAIL=... ADMIN_PASSWORD=...
node demo-data/seed.mjs --always-open
node demo-data/life.mjs
node demo-data/seed.mjs
PHOTOS_DIR=~/Downloads/archive/evaluation node demo-data/photos.mjs
```

and put the real mail back with the plain command (`docker compose -f compose.prod.yaml -f compose.demo.yaml --env-file .env.prod up -d backend`).
Without `DEMO_REDIS_CONTAINER` (the server's Redis is not on your computer) the seed waits out the registration limit of five
accounts per hour; set it only when running on the server itself.

## Start over

```bash
docker compose -f ../deploy/compose.dev.yaml down -v   # deletes the database volume
docker compose -f ../deploy/compose.dev.yaml --env-file ../deploy/.env up -d
# start the backend (Flyway recreates the schema), then seed again
```

## Limits

- Orders are placed "now", so revenue charts show the demo orders on the day the seed ran.
- Payments are cash on delivery; MoMo needs real sandbox credentials.
