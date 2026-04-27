# Event-Driven eCommerce Platform

An event-driven order processing platform built with Apache Kafka as the backbone for decoupled microservices. Demonstrates at-least-once, idempotent, and exactly-once delivery guarantees across real-world consumer patterns. The Notification Bridge shows how Kafka integrates with Google Cloud Pub/Sub for cross-system fan-out.

Part of the series: **Developing Intuition on Building Blocks — Part 3**
by Dhruv Jawalkar — Hands-On Project 2 of 2.

---

## Architecture

![Event-Driven eCommerce Platform — Architecture & Kafka Topology](Architecture%20Diagram/Event-Driven-eCommerce-Platform-Architecture-Kafka-Topology.png)

> Interactive diagram: https://link.excalidraw.com/l/5sVhqUivTXa/6XmscHWLlgc

### How it fits together

```
POST /orders
     │
     ▼
┌─────────────────┐   ORDER_PLACED (key=orderId)    ┌──────────────────────────┐
│  Order Service  │ ──────────────────────────────► │   orders topic (12 pts)  │
│  (idempotent    │                                  └──────────────────────────┘
│   producer)     │                                           │
└─────────────────┘                          ┌────────────────┼────────────────┐
        │                                    │                │                │
        ▼                                    ▼                ▼                ▼
  PostgreSQL                        ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐
  (orders +                         │  Inventory   │ │   Payment    │ │  Notification    │
   order_items)                     │  Service     │ │  Service     │ │  Bridge          │
                                    │  manual ack  │ │  txn API     │ │  manual ack      │
                                    │  idempotent  │ │  EOS         │ └──────────────────┘
                                    └──────────────┘ └──────────────┘         │
                                           │                │                 ▼
                                    PostgreSQL        payments topic    GCP Pub/Sub
                                    (inventory,       (12 pts,         (ecommerce-orders)
                                     reservations)    compacted)
```

**Order Service** accepts REST requests, persists orders to PostgreSQL, and publishes `OrderEvent` messages to the `orders` topic using an idempotent producer (`acks=all`, `enable.idempotence=true`). The `orderId` is the partition key, ensuring all events for a given order land on the same partition.

**Inventory Service** consumes from `orders` with manual acknowledgement and a DB-backed `messageId` deduplication table, giving at-least-once delivery with idempotent processing. Stock is reserved on `ORDER_PLACED` and released on `ORDER_CANCELLED`.

**Payment Service** uses the full Kafka transactional API — `beginTransaction` / `sendOffsetsToTransaction` / `commitTransaction` — so the consumer offset advance and the `PaymentEvent` publish are atomic. `isolation.level=read_committed` on the consumer ensures it never reads uncommitted payment records.

**Notification Bridge** manually acks Kafka only after GCP Pub/Sub confirms receipt, giving at-least-once bridging with no silent drops.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend services | Java 21, Spring Boot 3.x, spring-kafka |
| Message broker | Apache Kafka 3.5+ (3-node cluster via Confluent 7.5) |
| Cluster coordination | ZooKeeper |
| Cloud messaging | Google Cloud Pub/Sub |
| Database | PostgreSQL 15 |
| Observability | Confluent Control Center 7.5 |
| Build tool | Maven (multi-module) |
| Infrastructure | Docker Compose |

---

## Kafka Topology

### Topics

| Topic | Partitions | Key | Retention | Notes |
|-------|-----------|-----|-----------|-------|
| `orders` | 12 | `orderId` | 7 days | `acks=all`, `replication.factor=3` |
| `payments` | 12 | `orderId` | compacted | `cleanup.policy=compact` — latest status per order |
| `inventory-events` | 6 | `productId` | 30 days | published by Inventory Service |

### Cluster

```
replication.factor   = 3
min.insync.replicas  = 2      ← writes survive one broker failure
acks                 = all    ← on all business topic producers
auto.create.topics   = false  ← topics declared explicitly at startup
```

### Consumer Groups

| Group ID | Service | Delivery Guarantee | Key Config |
|----------|---------|-------------------|-----------|
| `inventory-service` | Inventory Service | At-least-once, idempotent | Manual ack, prefetch=50 |
| `payment-service` | Payment Service | Exactly-once end-to-end | Transactional API, `read_committed` |
| `notification-bridge` | Notification Bridge | At-least-once | Manual ack, ack after GCP confirm |

---

## Project Structure

```
event-driven-ecommerce/
├── docker-compose.yml
├── pom.xml                          ← Maven multi-module parent
├── shared/                          ← Shared event models (OrderEvent, PaymentEvent, enums)
├── order-service/                   ← REST API + idempotent Kafka producer
├── inventory-service/               ← Manual-ack consumer + DB idempotency
├── payment-service/                 ← Transactional consumer (exactly-once)
├── notification-bridge/             ← Kafka → GCP Pub/Sub bridge
├── Architecture Diagram/
│   └── Event-Driven-eCommerce-Platform-Architecture-Kafka-Topology.png
├── CLAUDE.md                        ← Authoritative topology, schemas, rules
└── INSTRUCTIONS.md                  ← Phased build plan for Claude Code
```

---

## Ports

| Service | Port | Purpose |
|---------|------|---------|
| Kafka broker 1 | 9092 | Client connections |
| Kafka broker 2 | 9093 | Client connections |
| Kafka broker 3 | 9094 | Client connections |
| ZooKeeper | 2181 | Cluster coordination |
| Control Center | 9021 | Confluent observability UI |
| PostgreSQL | 5432 | Shared database |
| Order Service | 8080 | REST API |

---

## Bringing Up the Infrastructure

### Prerequisites

- Docker Desktop (with Compose v2)
- Java 21 + Maven (for building application services)
- A GCP project with Pub/Sub enabled (Phase 5 only)

### Start the Kafka cluster + supporting services

```bash
docker compose up -d
```

This starts ZooKeeper, three Kafka brokers, the `kafka-setup` init container (creates all topics), Confluent Control Center, and PostgreSQL. The init container exits once topics are created — this is expected.

### Verify the cluster is healthy

```bash
# All brokers should be healthy
docker compose ps

# Confirm topics were created
docker exec kafka-1 kafka-topics --bootstrap-server localhost:29092 --list
```

Then open **http://localhost:9021** — Control Center should show:
- 3 healthy brokers
- Topics: `orders` (12 partitions), `payments` (12 partitions, compacted), `inventory-events` (6 partitions)
- Each topic: `replication.factor=3`, `min.insync.replicas=2`

### Stop everything

```bash
docker compose down          # keeps postgres-data volume
docker compose down -v       # also removes the postgres volume
```

---

## Building the Platform Phase by Phase with Claude Code

This project is designed to be built iteratively using Claude Code. Each phase adds one service and one new Kafka concept.

### Setup

```bash
# Install Claude Code if you haven't already
npm install -g @anthropic-ai/claude-code

# Open the project
cd event-driven-ecommerce
claude
```

### How to instruct Claude Code

Claude Code reads `CLAUDE.md` at the start of every session — it contains the authoritative Kafka topology, message schemas, and non-negotiable implementation rules. The detailed task list for each phase lives in `INSTRUCTIONS.md`.

**Start a phase by saying:**

```
Read CLAUDE.md and INSTRUCTIONS.md, then implement Phase <N>.
```

Claude Code will work through the tasks for that phase, write the code, and you can verify against the checklist before moving on.

---

## Phase-by-Phase Overview

### Phase 1 — Docker Compose, Kafka Cluster, Topic Declaration ✅

**Concept:** Cluster setup, topic configuration, replication guarantees.

What gets built: `docker-compose.yml` with a 3-node Kafka cluster, ZooKeeper, Control Center, PostgreSQL, and a `kafka-setup` init container that declares all topics with the correct partition counts, replication factors, and cleanup policies.

**Prompt to use:**
```
Read CLAUDE.md and INSTRUCTIONS.md, then implement Phase 1.
```

**Done when:** `docker-compose up` starts cleanly and Control Center at localhost:9021 shows all three topics with correct configuration.

---

### Phase 2 — Order Service: REST API + Idempotent Producer

**Concept:** Idempotent producer, `acks=all`, key-based partitioning.

What gets built: A Spring Boot service with `POST /orders` and `GET /orders/{id}`. Every order is persisted to PostgreSQL and published to the `orders` topic with `orderId` as the partition key and a UUID `messageId`. The producer is configured with `enable.idempotence=true` and `acks=all`.

**Prompt to use:**
```
Read CLAUDE.md and INSTRUCTIONS.md, then implement Phase 2.
```

**Done when:** Posting an order returns 201, the event appears in Control Center under Topics → orders → Messages, and 10 orders for the same `orderId` all land on the same partition.

---

### Phase 3 — Inventory Service: Manual Ack + Idempotency

**Concept:** At-least-once delivery, manual acknowledgement, DB-backed deduplication.

What gets built: A consumer in group `inventory-service` that manually acks only after committing a stock reservation and recording the `messageId` in a `processed_messages` table — both in the same `@Transactional`. Duplicate events with the same `messageId` are detected and skipped.

**Prompt to use:**
```
Read CLAUDE.md and INSTRUCTIONS.md, then implement Phase 3.
```

**Done when:** Sending a duplicate `ORDER_PLACED` (same `messageId`) results in exactly one stock reservation; stopping and restarting the service processes all missed events without duplicates.

---

### Phase 4 — Payment Service: Transactional Read-Process-Write

**Concept:** Exactly-once semantics, Kafka transactional API, `read_committed` isolation.

What gets built: A consumer in group `payment-service` that wraps the full read → charge → publish `PaymentEvent` → commit offset cycle in a Kafka transaction. If the payment gateway rejects (stub: amounts ≥ $10,000), the transaction aborts and the offset is NOT committed — the order is re-read on the next poll.

**Prompt to use:**
```
Read CLAUDE.md and INSTRUCTIONS.md, then implement Phase 4.
```

**Done when:** An order under $10,000 produces a CAPTURED event and advances the consumer offset; an order over $10,000 aborts with no entry in the payments table and no offset advance.

---

### Phase 5 — Notification Bridge: Kafka → GCP Pub/Sub

**Concept:** Cross-system at-least-once bridging, manual ack after external confirmation.

What gets built: A consumer in group `notification-bridge` that publishes `ORDER_PLACED` events to a GCP Pub/Sub topic synchronously. Kafka is acked only after GCP confirms receipt. If GCP fails, the message is not acked and Kafka redelivers it.

**Prerequisites:** A GCP project with a `ecommerce-orders` Pub/Sub topic and a service account JSON key set as `GOOGLE_APPLICATION_CREDENTIALS`.

**Prompt to use:**
```
Read CLAUDE.md and INSTRUCTIONS.md, then implement Phase 5.
```

**Done when:** Placing an order causes a message to appear in the GCP Pub/Sub console; simulating a GCP failure leaves the Kafka consumer lag non-zero until retry succeeds.

---

### Phase 6 — Integration Verification + Experiments

**Concept:** Full end-to-end flow, ISR behaviour under broker failure, compaction, partition rebalancing.

**Prompt to use:**
```
Read CLAUDE.md and INSTRUCTIONS.md, then help me run the Phase 6 verification and experiment prompts.
```

**Experiments included:**
1. Consumer lag recovery — stop Inventory Service, send 10 orders, restart, verify exactly-once reservations.
2. Broker failure under load — kill kafka-2 mid-publish, observe ISR drop, verify no message loss.
3. Transaction abort — send a $15,000 order, observe Payment Service abort, verify offset not committed.
4. Partition rebalancing — start a second Inventory Service instance, watch partitions redistribute in Control Center.
5. Log compaction — send multiple payment statuses for the same `orderId`, seek to earliest after compaction, only the final status remains.
6. Durability with reduced ISR — stop kafka-3, place 10 orders (ISR=2, still available), restart kafka-3, verify all 10 messages present.

---

## Message Schemas

### OrderEvent
```json
{
  "messageId":   "uuid",
  "eventType":   "ORDER_PLACED | ORDER_SHIPPED | ORDER_CANCELLED",
  "orderId":     "order-12345",
  "customerId":  "customer-alice",
  "items":       [{ "sku": "SKU-999", "qty": 2, "price": 29.99 }],
  "totalAmount": 59.98,
  "currency":    "USD",
  "timestamp":   1714000000000
}
```

### PaymentEvent
```json
{
  "messageId":     "uuid",
  "orderId":       "order-12345",
  "status":        "PENDING | AUTHORISED | CAPTURED | FAILED | REFUNDED",
  "amount":        59.98,
  "paymentMethod": "CARD",
  "timestamp":     1714000000000
}
```

---

## References

- Companion PDF: `Project2_EventDrivenEcommerce_Companion.pdf`
- Architecture diagram (interactive): https://link.excalidraw.com/l/5sVhqUivTXa/6XmscHWLlgc
- Spring Kafka docs: https://docs.spring.io/spring-kafka/docs/current/reference/html/
- Confluent Platform docs: https://docs.confluent.io/platform/current/
