# Deploying on AWS (EC2 + Docker Compose + Caddy)

One small EC2 instance runs the app (MongoDB backend), MongoDB, Redis and Caddy (automatic HTTPS).
Resume load-test numbers come from the controlled local benchmark (`loadtest/run_matrix.sh`), **not** from this instance.

## 1. Launch the instance
1. Set a **billing alarm** first (Billing → Budgets).
2. EC2 → Launch instance:
   - AMI: Amazon Linux 2023, type `t3.small` (2 GB RAM) or larger. Check current free-tier/credit terms.
   - Key pair: create/download one.
   - Security group: **80, 443** from anywhere; **22 from your IP only**.
   - Advanced → User data: paste `deploy/aws/user-data.sh`.
3. Allocate an **Elastic IP** and associate it, so the address survives restarts.
4. Point a DNS `A` record (e.g. `sho.example.com`) at the Elastic IP. Caddy needs it to issue the TLS certificate.

## 2. Deploy
```bash
ssh -i key.pem ec2-user@<elastic-ip>
git clone <your-repo-url> url-shortener && cd url-shortener
cp deploy/aws/.env.example deploy/aws/.env
sed -i "s/^ADMIN_TOKEN=.*/ADMIN_TOKEN=$(openssl rand -hex 32)/" deploy/aws/.env
nano deploy/aws/.env            # set DOMAIN
docker compose -f deploy/aws/docker-compose.prod.yml --env-file deploy/aws/.env up -d --build
curl -s https://<your-domain>/actuator/health
```

## 3. Issue an API key (admin endpoints are blocked by Caddy; call the app directly on the instance)
The app image has no curl, so use a throwaway curl container on the stack's network
(Compose names it `<project>_default`; the project is `aws` because the file lives in `deploy/aws`):
```bash
source deploy/aws/.env
docker run --rm --network aws_default curlimages/curl -s -X POST http://app:8080/admin/keys \
  -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' -d '{"name":"me"}'
```
The response contains `apiKey`. It is shown once; only its SHA-256 hash is stored.

## 4. Use it
```bash
curl -s -X POST https://<domain>/api/links -H "X-API-Key: <key>" -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com","ttlSeconds":86400}'
curl -sI https://<domain>/<code>     # 302
```

## Option B: managed services
Set `MONGO_URI` in `.env` to a MongoDB Atlas cluster (same region), and remove the `mongo` service.
A managed Redis (ElastiCache, or Upstash) works the same way via `REDIS_HOST`.

## Updating
```bash
git pull && docker compose -f deploy/aws/docker-compose.prod.yml --env-file deploy/aws/.env up -d --build app
```
