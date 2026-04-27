# Event-Driven eCommerce Platform — Project Memory

## What This Project Is

An event-driven order processing platform demonstrating Apache Kafka as the
backbone for decoupled microservices. The Notification Bridge integrates Kafka
with Google Cloud Pub/Sub, showing how the two messaging models complement each
other in a real system.

Part of the series: **Developing Intuition on Building Blocks — Part 3**
by Dhruv Jawalkar. Hands-On Project 2 of 2.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend services | Java 21, Spring Boot 3.x, spring-kafka |
| Message broker | Apache Kafka 3.5+ (3-node cluster) |
| Coordination | ZooKeeper (or KRaft mode as extension) |
| Cloud messaging | Google Cloud Pub/Sub (notification fan-out) |
| Database | PostgreSQL 15 |
| Observability | Confluent Control Center 7.5 |
| Build tool | Maven (multi-module project) |
| Infrastructure | Docker Compose |

---

## Project Structure

```
event-driven-ecommerce/
├── CLAUDE.md                          ← This file
├── INSTRUCTIONS.md                    ← Phased build plan
├── docker-compose.yml
├── order-service/
│   └── src/main/java/com/ecommerce/orderservice/
│       ├── OrderServiceApplication.java
│       ├── config/KafkaProducerConfig.java
│       ├── controller/OrderController.java
│       ├── service/OrderService.java
│       ├── repository/OrderRepository.java
│       └── model/{Order, OrderEvent, OrderItem}.java
├── inventory-service/
│   └── src/main/java/com/ecommerce/inventoryservice/
│       ├── consumer/InventoryConsumer.java
│       ├── config/KafkaConsumerConfig.java
│       ├── service/InventoryService.java
│       └── repository/{InventoryRepository, ProcessedMessageRepository}.java
├── payment-service/
│   └── src/main/java/com/ecommerce/paymentservice/
│       ├── consumer/PaymentConsumer.java
│       ├── config/KafkaTransactionalConfig.java
│       ├── service/PaymentGatewayService.java
│       └── repository/PaymentRepository.java
├── notification-bridge/
│   └── src/main/java/com/ecommerce/notificationbridge/
│       ├── consumer/NotificationBridgeConsumer.java
│       ├── config/{KafkaConsumerConfig, GcpPubSubConfig}.java
│       └── service/PubSubPublisherService.java
└── shared/
    └── src/main/java/com/ecommerce/shared/
        └── model/{OrderEvent, PaymentEvent}.java
```

---

## Kafka Topology (DO NOT CHANGE)

### Topics

| Name | Partitions | Key | Retention | Notes |
|------|-----------|-----|-----------|-------|
| `orders` | 12 | orderId | 7 days | acks=all, replication.factor=3 |
| `payments` | 12 | orderId | compact | cleanup.policy=compact — latest status per order |
| `inventory-events` | 6 | productId | 30 days | published by Inventory Service |

### Cluster Configuration

```
replication.factor=3
min.insync.replicas=2
acks=all on all business topic producers
```

### Consumer Groups

| Group ID | Service | Ack Mode | Special Config |
|----------|---------|----------|----------------|
| `inventory-service` | Inventory Service | Manual | prefetch=50, idempotent |
| `payment-service` | Payment Service | Transactional | transactional.id=payment-processor-{n}, isolation.level=read_committed |
| `notification-bridge` | Notification Bridge | Manual | at-least-once, bridges to GCP Pub/Sub |

---

## Delivery Guarantees by Service

| Service | Guarantee | Mechanism |
|---------|-----------|-----------|
| Order Service (producer) | At-least-once, no broker-side duplicates | acks=all + enable.idempotence=true |
| Inventory Service | At-least-once | Manual ack + messageId deduplication in DB |
| Payment Service | Exactly-once end-to-end | Transactional API: beginTransaction / sendOffsetsToTransaction / commitTransaction |
| Notification Bridge | At-least-once | Manual ack + GCP Pub/Sub at-least-once |

---

## Message Schemas

### OrderEvent (JSON)
```json
{
  "messageId":   "UUID — set by Order Service producer",
  "eventType":   "ORDER_PLACED | ORDER_SHIPPED | ORDER_CANCELLED",
  "orderId":     "order-12345",
  "customerId":  "customer-alice",
  "items":       [{"sku": "SKU-999", "qty": 2, "price": 29.99}],
  "totalAmount": 59.98,
  "currency":    "USD",
  "timestamp":   1714000000000
}
```

### PaymentEvent (JSON — written to compacted payments topic)
```json
{
  "messageId":     "UUID",
  "orderId":       "order-12345",
  "status":        "PENDING | AUTHORISED | CAPTURED | FAILED | REFUNDED",
  "amount":        59.98,
  "paymentMethod": "CARD",
  "timestamp":     1714000000000
}
```

---

## Non-Negotiable Implementation Rules

1. **12 partitions on orders topic** — declared at startup, never changed
2. **key=orderId on all order event publishes** — partitioning must be key-based
3. **enable.idempotence=true on Order Service producer** — always paired with acks=all
4. **Payment Service uses transactional API** — beginTransaction / sendOffsetsToTransaction / commitTransaction
5. **isolation.level=read_committed on Payment consumer** — not read_uncommitted
6. **Manual ack on Inventory consumer** — AcknowledgementMode.MANUAL, prefetch=50
7. **messageId set by Order Service** — UUID, used for idempotency in Inventory Service
8. **payments topic cleanup.policy=compact** — declared at topic creation
9. **3-node Kafka cluster** — replication.factor=3, min.insync.replicas=2
10. **Notification Bridge does NOT ack until GCP Pub/Sub confirms** — at-least-once bridging

---

## Ports

| Service | Port | Purpose |
|---------|------|---------|
| Kafka (broker 1) | 9092 | AMQP |
| Kafka (broker 2) | 9093 | AMQP |
| Kafka (broker 3) | 9094 | AMQP |
| ZooKeeper | 2181 | Cluster coordination |
| Control Center | 9021 | Confluent observability UI |
| PostgreSQL | 5432 | Shared database instance |
| Order Service | 8080 | REST API |

---

## Current Build Status

- [x] Phase 1 — Docker Compose, Kafka cluster, topic declaration
- [ ] Phase 2 — Order Service: REST API + idempotent producer
- [ ] Phase 3 — Inventory Service: manual ack consumer + idempotency
- [ ] Phase 4 — Payment Service: transactional read-process-write
- [ ] Phase 5 — Notification Bridge: Kafka → GCP Pub/Sub
- [ ] Phase 6 — Integration verification + experiment prompts

---

## References

- Companion PDF: `Project2_EventDrivenEcommerce_Companion.pdf`
- Architecture diagram: https://link.excalidraw.com/l/5sVhqUivTXa/6XmscHWLlgc
- Article series: Developing Intuition on Building Blocks — Part 3
- Spring Kafka docs: https://docs.spring.io/spring-kafka/docs/current/reference/html/
- Confluent Kafka docs: https://docs.confluent.io/platform/current/
