# ====================================================
# Cloudflare R2 Configuration for Anomaly Snapshots
# ====================================================

# Enable Cloudflare R2 storage for anomaly snapshots
CLOUDFLARE_R2_ENABLED=true

# R2 Account credentials
CLOUDFLARE_R2_ACCESS_KEY_ID=0f570695dfe0817da59956c672b68e78
CLOUDFLARE_R2_SECRET_ACCESS_KEY=e2e923945cd6aabd7e0d96e516fb880d69bd6d08bfdc9bff3e73cc9d25fd00a0

# R2 Account and bucket configuration
CLOUDFLARE_R2_ACCOUNT_ID=1234567890abcdef1234567890abcdef
CLOUDFLARE_R2_BUCKET_NAME=snapshots-medicaux
CLOUDFLARE_R2_ENDPOINT=https://1234567890abcdef1234567890abcdef.r2.cloudflarestorage.com

# Optional: Region and object prefix
CLOUDFLARE_R2_REGION=auto
CLOUDFLARE_R2_OBJECT_PREFIX=snapshots

# R2 Endpoint (EU region)
CLOUDFLARE_R2_ENDPOINT=https://d70c49e8d140c8a22ed2b4466b247b79.r2.cloudflarestorage.com

# R2 Bucket name
CLOUDFLARE_R2_BUCKET_NAME=platformiot

# R2 Region
CLOUDFLARE_R2_REGION=auto

# Object storage prefix for snapshots
CLOUDFLARE_R2_OBJECT_PREFIX=anomalies

# ====================================================
# Note: This .env.r2 file contains sensitive credentials.
# DO NOT commit to version control.
# Use: docker-compose --env-file .env.r2 up -d --build
# ====================================================
