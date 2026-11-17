#!/usr/bin/env bash
# Benchmark matrix: {postgres, mongo} x {cache off, on} x RATES, REPEATS runs each.
# Each run: flush Redis -> warm-up -> reset Redis stats -> measured run -> record cache hits/misses.
# Results: results/raw/*.json (k6 summaries), results/raw/*.redis.txt, results/results.md (table).
#
#   ./loadtest/run_matrix.sh                                  # full matrix (~1h45m)
#   RATES="1000 5000" REPEATS=1 DURATION=30s WARMUP=10s ./loadtest/run_matrix.sh   # quick pass
set -euo pipefail
cd "$(dirname "$0")/.."

RATES="${RATES:-500 1000 2000 3000 5000}"
CONFIGS="${CONFIGS:-postgres:false postgres:true mongo:false mongo:true}"
REPEATS="${REPEATS:-3}"
DURATION="${DURATION:-60s}"
WARMUP="${WARMUP:-30s}"
SEED_COUNT="${SEED_COUNT:-100000}"
SKEW="${SKEW:-3}"
export ADMIN_TOKEN="${ADMIN_TOKEN:-loadtest-admin-token}"
RAW=results/raw
mkdir -p "$RAW"

redis_cli() { docker compose exec -T redis redis-cli "$@"; }

k6_run() { docker compose --profile loadtest run --rm -T k6 run --quiet "$@"; }

start_app() {
  echo ">> app: STORAGE=$1 CACHE=$2"
  STORAGE="$1" CACHE="$2" docker compose up -d --no-deps --force-recreate app >/dev/null
  for _ in $(seq 1 120); do
    if curl -sf localhost:8080/actuator/health >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "app did not become healthy"; docker compose logs --tail 80 app; exit 1
}

ensure_seeded() {
  local storage="$1" file="loadtest/codes_$1.json"
  if [[ -s "$file" ]]; then
    local first
    first=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))[0])' "$file")
    if [[ "$(curl -s -o /dev/null -w '%{http_code}' "localhost:8080/$first")" == "302" ]]; then
      echo ">> $storage already seeded ($file)"; return 0
    fi
  fi
  echo ">> seeding $SEED_COUNT links into $storage"
  curl -sf -X POST -H "X-Admin-Token: $ADMIN_TOKEN" \
    "localhost:8080/admin/seed?count=$SEED_COUNT&expiringPercent=20" -o "$file"
  echo ">> seeded $(python3 -I -c 'import json,sys; print(len(json.load(open(sys.argv[1]))))' "$file") codes"
}

echo ">> starting stores and building app image"
docker compose up -d --wait redis postgres mongo
docker compose build app

{
  echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "host: $(uname -sm), $(sysctl -n hw.ncpu 2>/dev/null || nproc) CPUs"
  echo "rates: $RATES | repeats: $REPEATS | duration: $DURATION | warmup: $WARMUP | skew exponent: $SKEW | seeded links: $SEED_COUNT"
  echo "limits: app 2 CPU/1 GB, postgres 2 CPU/1 GB, mongo 2 CPU/1 GB, redis 1 CPU/512 MB; k6 on the same host (unlimited)"
} > results/conditions.txt

for cfg in $CONFIGS; do
  storage="${cfg%%:*}"
  cache="${cfg##*:}"
  start_app "$storage" true
  ensure_seeded "$storage"
  [[ "$cache" == "true" ]] || start_app "$storage" "$cache"

  for rate in $RATES; do
    for rep in $(seq 1 "$REPEATS"); do
      name="${storage}_cache-${cache}_${rate}_r${rep}"
      echo ">> $name"
      common=(-e BASE=http://app:8080 -e "CODES=/scripts/codes_${storage}.json" -e "RATE=$rate" -e "SKEW=$SKEW")
      redis_cli FLUSHALL >/dev/null
      k6_run "${common[@]}" -e "DURATION=$WARMUP" /scripts/redirects.js >/dev/null 2>&1 || true
      redis_cli CONFIG RESETSTAT >/dev/null
      k6_run "${common[@]}" -e "DURATION=$DURATION" -e "SUMMARY=/results/raw/$name.json" /scripts/redirects.js || true
      redis_cli INFO stats | tr -d '\r' | grep -E '^keyspace_(hits|misses):' > "$RAW/$name.redis.txt"
    done
  done
done

python3 -I loadtest/summarize.py "$RAW" results/conditions.txt > results/results.md
echo ">> done: results/results.md"
cat results/results.md
