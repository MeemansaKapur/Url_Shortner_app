# Load test results

```
date: 2026-11-15T10:01:22Z
host: Darwin arm64, 10 CPUs
rates: 1000 5000 | repeats: 1 | duration: 20s | warmup: 10s | skew exponent: 3 | seeded links: 100000
limits: app 2 CPU/1 GB, postgres 2 CPU/1 GB, mongo 2 CPU/1 GB, redis 1 CPU/512 MB; k6 on the same host (unlimited)
```

*Max sustainable RPS: highest tested rate with p95 < 50 ms, errors < 1%, and ≥ 95% of the target rate achieved. Median of repeats.*

## Summary

| Config | Max sustainable RPS | p50 | p95 | p99 | Cache hit rate |
|---|---|---|---|---|---|
| PostgreSQL, no cache | 5,000 | 0.16 ms | 0.31 ms | 1.71 ms | – |
| PostgreSQL + Redis | 5,000 | 0.19 ms | 0.48 ms | 2.69 ms | 68.6% |
| MongoDB, no cache | 5,000 | 0.20 ms | 0.38 ms | 1.99 ms | – |
| MongoDB + Redis | 5,000 | 0.19 ms | 0.54 ms | 3.52 ms | 69.8% |

## Cache effect (same backend, same rate)

| Backend | Rate | p95 no cache | p95 with Redis | Hit rate |
|---|---|---|---|---|
| MongoDB | 1,000/s | 0.34 ms | 0.49 ms | 38.4% |
| MongoDB | 5,000/s | 0.38 ms | 0.54 ms | 69.8% |
| PostgreSQL | 1,000/s | 0.29 ms | 0.39 ms | 37.8% |
| PostgreSQL | 5,000/s | 0.31 ms | 0.48 ms | 68.6% |

## All runs

| Config | Target rate | Achieved | p50 | p95 | p99 | Errors | Hit rate | Meets SLO | Runs |
|---|---|---|---|---|---|---|---|---|---|
| PostgreSQL, no cache | 1,000/s | 1,000/s | 0.20 ms | 0.29 ms | 0.74 ms | 0.00% | – | yes | 1 |
| PostgreSQL, no cache | 5,000/s | 5,000/s | 0.16 ms | 0.31 ms | 1.71 ms | 0.00% | – | yes | 1 |
| PostgreSQL + Redis | 1,000/s | 1,000/s | 0.31 ms | 0.39 ms | 0.70 ms | 0.00% | 37.8% | yes | 1 |
| PostgreSQL + Redis | 5,000/s | 5,000/s | 0.19 ms | 0.48 ms | 2.69 ms | 0.00% | 68.6% | yes | 1 |
| MongoDB, no cache | 1,000/s | 1,000/s | 0.22 ms | 0.34 ms | 1.35 ms | 0.00% | – | yes | 1 |
| MongoDB, no cache | 5,000/s | 5,000/s | 0.20 ms | 0.38 ms | 1.99 ms | 0.00% | – | yes | 1 |
| MongoDB + Redis | 1,000/s | 1,000/s | 0.36 ms | 0.49 ms | 1.05 ms | 0.00% | 38.4% | yes | 1 |
| MongoDB + Redis | 5,000/s | 5,000/s | 0.19 ms | 0.54 ms | 3.52 ms | 0.00% | 69.8% | yes | 1 |
