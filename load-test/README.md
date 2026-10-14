# Load test

`bonbon.js` is a [k6](https://k6.io) script for the busiest paths, run against the demo data
(`demo-data/seed.mjs --always-open`):

| Scenario | Load | Requests |
|---|---|---|
| `browse` | `VUS` users (default 50) looping for `DURATION` (default 2m) | shops in the area (`GET /api/vendors?lat&lng`), a search (`&q=`), a shop's menu |
| `order` | `ORDER_RATE` orders per second (default 2) | place a cash order as one of the 4 demo customers, then cancel it (so the 3-open-orders limit never fills) |

Thresholds: error rate < 1 % per scenario; p95 area < 300 ms, search < 400 ms, menu < 300 ms, order < 800 ms.

```bash
# from backend/, with Docker; host.docker.internal reaches the host from the k6 container
docker run --rm -i -e API=http://host.docker.internal:8080 grafana/k6 run - < load-test/bonbon.js
# a lighter run
docker run --rm -i -e API=http://host.docker.internal:8080 -e VUS=10 -e DURATION=30s grafana/k6 run - < load-test/bonbon.js
```

## Results (2026-10-13)

Machine: one Windows 11 laptop running everything (16 logical CPUs; Docker Desktop VM with 16 CPU / 8 GB).
Target: the production stack in Docker (`deploy/compose.prod.yaml` + `compose.rehearsal.yaml` in the umbrella repo:
Caddy → backend image on a Linux JRE → PostgreSQL 17, Redis 7.4), k6 in a container on the same machine, so the
numbers are a lower bound for a dedicated server. 50 browsing users + 2 orders/s for 2 minutes.

| Data | Version | Requests/s | Errors | p95 area | p95 search | p95 menu | p95 order |
|---|---|---|---|---|---|---|---|
| Demo (6 shops) | before | 993 | 0 % | 93 ms | 87 ms | 94 ms | 231 ms |
| 20 000 shops, 100 000 dishes | before | 87 | 0.36 % | 906 ms | 1.13 s | 834 ms | 1.41 s |
| 20 000 shops, 100 000 dishes | after (V0041) | 999 | 0.00 % | 88 ms | 83 ms | 89 ms | 254 ms |

The 20 000 synthetic shops were inserted into the rehearsal database only (random points over Vietnam, each with
the dishes of a demo shop), to see how the area query scales.

**What was slow.** The area query's prefilter compares the customer's point with a box computed per shop from its own
radius (`v.lat - v.delivery_radius_km / 111`…), which no index can serve, so every request scanned all approved shops
and computed `cos()` per row. `EXPLAIN ANALYZE` at 20 000 shops: parallel sequential scan, 20 000 rows removed by
filter, **12.5 ms**. Under 50 concurrent users these scans saturated the database CPU and slowed every endpoint.

**Fix (V0041).** A latitude band as wide as the largest approved radius goes first
(`v.lat between :lat ± max(radius) / 111`), served by a partial index on `vendors(lat)`; `max(radius)` comes from the
end of a second partial index. Same plan, now a bitmap index scan reading 84 rows: **0.56 ms**, same 6 results.
Search and menu needed nothing more: the search's dish lookup only runs for the shops left by the area filter, and
the menu reads by primary and foreign keys.

## Not covered

- MoMo payment (no sandbox credentials), WebSocket order updates, uploads.
- A separate machine for k6 and a real VPS: the next run should be on the server the demo uses.
