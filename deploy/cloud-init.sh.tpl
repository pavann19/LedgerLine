#!/bin/bash
# Rendered by Terraform's templatefile() with: aws_region, ecr_registry, image_tag,
# db_endpoint, db_password. Runs once at EC2 first boot to actually stand up the app,
# not just install Docker.
set -euxo pipefail

dnf update -y
dnf install -y docker git aws-cli
systemctl enable --now docker
usermod -aG docker ec2-user

# psql client, so the invariant-check SQL queries can be run directly against RDS from here
# (RDS is not publicly accessible — reach this host via SSM Session Manager, not SSH).
dnf install -y postgresql16 || dnf install -y postgresql15 || true

# Docker Compose v2 plugin (arm64 build, matching the t4g/Graviton2 host).
mkdir -p /usr/local/lib/docker/cli-plugins
curl -SL "https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-aarch64" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

# Authenticate to ECR using the instance's IAM role — no long-lived credentials on the box.
aws ecr get-login-password --region "${aws_region}" \
  | docker login --username AWS --password-stdin "${ecr_registry}"

mkdir -p /opt/ledgerline
cat > /opt/ledgerline/compose.yaml <<COMPOSE
services:
  kafka:
    image: apache/kafka:3.8.0
    container_name: ledgerline-kafka
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
    restart: unless-stopped

  ledger-service:
    image: ${ecr_registry}/ledgerline/ledger-service:${image_tag}
    container_name: ledgerline-ledger-service
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://${db_endpoint}/ledgerline
      SPRING_DATASOURCE_USERNAME: ledger_admin
      SPRING_DATASOURCE_PASSWORD: "${db_password}"
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      LEDGER_OUTBOX_RELAY_ENABLED: "true"
      LEDGER_OUTBOX_RELAY_DELAY_MS: "500"
    depends_on:
      - kafka
    restart: unless-stopped

  projection-service:
    image: ${ecr_registry}/ledgerline/projection-service:${image_tag}
    container_name: ledgerline-projection-service
    ports:
      - "8081:8081"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://${db_endpoint}/ledgerline
      SPRING_DATASOURCE_USERNAME: ledger_admin
      SPRING_DATASOURCE_PASSWORD: "${db_password}"
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    depends_on:
      - kafka
    restart: unless-stopped

  prometheus:
    image: prom/prometheus:v2.53.0
    container_name: ledgerline-prometheus
    ports:
      - "9090:9090"
    volumes:
      - /opt/ledgerline/prometheus.yml:/etc/prometheus/prometheus.yml
    command:
      - "--config.file=/etc/prometheus/prometheus.yml"
    restart: unless-stopped
COMPOSE

cat > /opt/ledgerline/prometheus.yml <<'PROM'
global:
  scrape_interval: 15s
scrape_configs:
  - job_name: 'ledger-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['ledger-service:8080']
  - job_name: 'projection-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['projection-service:8081']
PROM

cd /opt/ledgerline
docker compose pull
docker compose up -d
