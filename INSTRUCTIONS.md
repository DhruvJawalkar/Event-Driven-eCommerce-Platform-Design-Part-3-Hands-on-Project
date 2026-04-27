# Phased Implementation Instructions
# Event-Driven eCommerce Platform — Claude Code Build Plan

Read CLAUDE.md before starting any phase. It contains the authoritative
topology, schemas, delivery guarantees, and non-negotiable rules.

After completing each phase, verify all checklist items before proceeding.
Update "Current Build Status" in CLAUDE.md after each phase.

---

## Phase 1 — Docker Compose, Kafka Cluster, Topic Declaration

**Goal:** `docker-compose up` starts a 3-node Kafka cluster with all topics
declared. Confluent Control Center shows all three topics with correct partition
counts. No application services needed yet.

### Tasks

**1.1 Create Maven multi-module parent pom.xml**
- Modules: `shared`, `order-service`, `inventory-service`, `payment-service`, `notification-bridge`
- Spring Boot parent 3.x, Java 21
- Shared dependencies: `spring-boot-starter-web`, `spring-kafka`, `jackson-databind`, `lombok`, `postgresql`

**1.2 Create shared module**
- `OrderEvent` record with all fields from CLAUDE.md schema
- `PaymentEvent` record
- `EventType` enum: ORDER_PLACED, ORDER_SHIPPED, ORDER_CANCELLED
- `PaymentStatus` enum: PENDING, AUTHORISED, CAPTURED, FAILED, REFUNDED

**1.3 Create docker-compose.yml**
- ZooKeeper: `confluentinc/cp-zookeeper:7.5.0`, port 2181
- kafka-1, kafka-2, kafka-3: `confluentinc/cp-kafka:7.5.0`
  - KAFKA_DEFAULT_REPLICATION_FACTOR=3
  - KAFKA_MIN_INSYNC_REPLICAS=2
  - Ports: 9092, 9093, 9094 respectively
- Control Center: `confluentinc/cp-enterprise-control-center:7.5.0`, port 9021
- PostgreSQL: `postgres:15`, port 5432
- Healthchecks on all Kafka brokers before dependent services start

**1.4 Topic declaration on startup (TopicConfig.java in order-service)**
Declare the following via `NewTopic` beans:

```java
// orders topic
@Bean
public NewTopic ordersTopic() {
    return TopicBuilder.name("orders")
        .partitions(12)
        .replicas(3)
        .config(TopicConfig.RETENTION_MS_CONFIG, "604800000")   // 7 days
        .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2")
        .build();
}

// payments topic — log compaction
@Bean
public NewTopic paymentsTopic() {
    return TopicBuilder.name("payments")
        .partitions(12)
        .replicas(3)
        .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT)
        .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2")
        .build();
}

// inventory-events topic
@Bean
public NewTopic inventoryEventsTopic() {
    return TopicBuilder.name("inventory-events")
        .partitions(6)
        .replicas(3)
        .config(TopicConfig.RETENTION_MS_CONFIG, "2592000000")  // 30 days
        .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2")
        .build();
}
```

### Verification Checklist
- [ ] `docker-compose up` starts without errors
- [ ] http://localhost:9021 shows Control Center UI
- [ ] Topics tab shows: `orders` (12 partitions), `payments` (12 partitions, compacted), `inventory-events` (6 partitions)
- [ ] Each topic shows replication.factor=3, min.insync.replicas=2
- [ ] All 3 brokers show as healthy

---

## Phase 2 — Order Service: REST API + Idempotent Producer

**Goal:** POST /orders persists to PostgreSQL and publishes an ORDER_PLACED
event to Kafka with orderId as the key. Publisher confirms via callback. Message
appears in Control Center with correct partition assignment.

### Tasks

**2.1 PostgreSQL schema (orders)**
```sql
CREATE TABLE IF NOT EXISTS orders (
    id           VARCHAR(36) PRIMARY KEY,
    customer_id  VARCHAR(100) NOT NULL,
    total_amount DECIMAL(10,2) NOT NULL,
    currency     VARCHAR(3) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'PLACED',
    created_at   TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS order_items (
    id        BIGSERIAL PRIMARY KEY,
    order_id  VARCHAR(36) REFERENCES orders(id),
    sku       VARCHAR(50) NOT NULL,
    quantity  INT NOT NULL,
    price     DECIMAL(10,2) NOT NULL
);
```

**2.2 KafkaProducerConfig.java**
- `acks=all`
- `enable.idempotence=true`
- `retries=Integer.MAX_VALUE`
- `batch.size=16384`
- `linger.ms=5`
- `compression.type=snappy`
- Value serializer: `JsonSerializer`

**2.3 OrderService.java**
- `placeOrder(PlaceOrderRequest)` → save to DB → publish to Kafka
- Generate `messageId` = UUID before publish
- Key = `orderId`
- Log partition and offset from send callback

**2.4 OrderController.java**
- `POST /orders` → returns 201 with order ID
- `GET /orders/{id}` → returns order from DB

**2.5 application.yml**
- Kafka bootstrap servers from env vars
- PostgreSQL datasource from env vars
- `spring.kafka.producer.transaction-id-prefix` — NOT set here (only in Payment Service)

### Verification Checklist
- [ ] POST /orders returns 201 with order ID
- [ ] Order appears in orders table in PostgreSQL
- [ ] ORDER_PLACED event appears in Control Center → Topics → orders → Messages
- [ ] Message key = orderId (visible in Control Center)
- [ ] Correct partition assigned based on key hash
- [ ] Send callback logs partition and offset
- [ ] 10 orders for same orderId all land on same partition (verify in Control Center)

---

## Phase 3 — Inventory Service: Manual Ack + Idempotency

**Goal:** Inventory Service consumes from orders topic, reserves stock on
ORDER_PLACED, releases on ORDER_CANCELLED. Idempotent — sending the same
orderId twice results in exactly one stock change.

### Tasks

**3.1 PostgreSQL schema (inventory)**
```sql
CREATE TABLE IF NOT EXISTS inventory (
    sku           VARCHAR(50) PRIMARY KEY,
    available_qty INT NOT NULL,
    reserved_qty  INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS processed_messages (
    message_id   VARCHAR(36) PRIMARY KEY,
    processed_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS stock_reservations (
    id          BIGSERIAL PRIMARY KEY,
    order_id    VARCHAR(36) NOT NULL,
    sku         VARCHAR(50) NOT NULL,
    quantity    INT NOT NULL,
    created_at  TIMESTAMP DEFAULT NOW()
);
```

**3.2 KafkaConsumerConfig.java (inventory)**
- Group ID: `inventory-service`
- `AcknowledgementMode.MANUAL`
- `setPrefetchCount(50)`
- `setAutoOffsetReset("earliest")`

**3.3 InventoryConsumer.java**
- Check `processedMessageRepository.existsById(event.messageId())` before processing
- If exists: ack and return
- If ORDER_PLACED: reserve stock + save to processed_messages (same @Transactional)
- If ORDER_CANCELLED: release reservation + save to processed_messages
- On success: ack
- On InsufficientStockException: ack (publish compensating event — out of scope for this phase)
- On other exception: do NOT ack — redeliver

### Verification Checklist
- [ ] Inventory Service starts and shows in Control Center → Consumer Groups → inventory-service
- [ ] Consumer lag = 0 after processing all queued messages
- [ ] Place an order → stock reserved in inventory table
- [ ] Cancel the order → stock released
- [ ] Send duplicate ORDER_PLACED (same messageId) → only 1 reservation in DB (idempotency)
- [ ] Stop Inventory Service → send 5 orders → restart → all 5 processed correctly

---

## Phase 4 — Payment Service: Transactional Read-Process-Write

**Goal:** Payment Service consumes ORDER_PLACED events, processes payment, and
writes to the payments topic AND commits the consumer offset atomically.
Aborting the transaction leaves the consumer offset unchanged — the order is
re-read on next poll.

### Tasks

**4.1 PostgreSQL schema (payments)**
```sql
CREATE TABLE IF NOT EXISTS payments (
    id             BIGSERIAL PRIMARY KEY,
    order_id       VARCHAR(36) NOT NULL UNIQUE,
    message_id     VARCHAR(36) NOT NULL,
    status         VARCHAR(20) NOT NULL,
    amount         DECIMAL(10,2) NOT NULL,
    payment_method VARCHAR(50),
    created_at     TIMESTAMP DEFAULT NOW()
);
```

**4.2 KafkaTransactionalConfig.java**
- Producer: `transactional.id=payment-processor-1`
  (use instance ID suffix for multiple instances)
- `enable.idempotence=true` (auto-set with transactional.id)
- `acks=all` (auto-set)
- Consumer: `isolation.level=read_committed`
- Consumer: `enable.auto.commit=false`
- Container factory: `ChainedKafkaTransactionManager`

**4.3 PaymentConsumer.java**
- Only process `ORDER_PLACED` events
- Call `paymentGatewayService.charge(...)` (stub implementation returning success/failure)
- Publish `PaymentEvent` to `payments` topic
- Save to payments table
- Use `@Transactional("kafkaTransactionManager")` — offset commit is atomic with produce

**4.4 PaymentGatewayService.java (stub)**
- Returns success for all amounts < $10,000
- Returns failure for amounts >= $10,000 (for testing abort)

### Verification Checklist
- [ ] Place an order under $10,000 → CAPTURED event in payments topic → payments table entry
- [ ] Place an order over $10,000 → transaction aborts → NO entry in payments table → order re-read on next poll
- [ ] Verify in Control Center that payment-service offset advances ONLY on commit (not on start of processing)
- [ ] View __transaction_state topic — observe open and committed transaction markers
- [ ] Send duplicate order → second processing attempt detected and skipped

---

## Phase 5 — Notification Bridge: Kafka → GCP Pub/Sub

**Goal:** Notification Bridge consumes ORDER_PLACED events and publishes to
GCP Pub/Sub. Does not ack until GCP confirms. Kafka redeliver if GCP publish fails.

### Tasks

**5.1 GCP Pub/Sub setup**
- Create topic: `ecommerce-orders` in your GCP project
- Create service account with Pub/Sub Publisher role
- Set GOOGLE_APPLICATION_CREDENTIALS env var in docker-compose

**5.2 GcpPubSubConfig.java**
- Configure `Publisher` bean pointing to `ecommerce-orders` topic

**5.3 KafkaConsumerConfig.java (notification-bridge)**
- Group ID: `notification-bridge`
- `AcknowledgementMode.MANUAL`
- `setAutoOffsetReset("earliest")`

**5.4 NotificationBridgeConsumer.java**
- Only bridge `ORDER_PLACED` events
- Publish to GCP Pub/Sub synchronously (`.get()` on the future)
- Ack only after GCP confirms
- Do NOT ack on GCP failure — Kafka will redeliver

**5.5 application.yml (notification-bridge)**
- Kafka bootstrap servers from env vars
- GCP project ID from env vars

### Verification Checklist
- [ ] Notification Bridge shows as consumer group in Control Center
- [ ] Place an order → message appears in GCP Pub/Sub (verify via GCP console)
- [ ] Consumer lag = 0 after bridge processes all messages
- [ ] Simulate GCP failure (invalid topic) → Kafka message NOT acked → redeliver on retry

---

## Phase 6 — Integration Verification + Experiment Prompts

**Goal:** Full end-to-end flow verified across all services. All concepts
observable in Control Center.

### Tasks

**6.1 End-to-end smoke test**
Place 20 orders via POST /orders. Verify:
- All 20 in orders table
- All 20 stock reservations in inventory table
- All 20 CAPTURED events in payments table (assuming < $10,000)
- All 20 events in GCP Pub/Sub
- Consumer lag = 0 for all three groups

**6.2 Verify ISR and replication**
- All 3 brokers running → verify replication.factor=3, ISR=3
- Stop kafka-2 → verify ISR=2, writes still succeed (min.insync.replicas=2 satisfied)
- Stop kafka-3 → verify ISR=1, writes FAIL with NotEnoughReplicasException
- Restart both → verify ISR returns to 3

**6.3 Update CLAUDE.md**
Mark all phases complete in "Current Build Status".

### Experiment Prompts

1. Stop inventory-service. Send 10 orders. Watch consumer lag grow in Control Center.
   Restart. Verify all 10 reservations made exactly once (no duplicates).

2. Kill kafka-2 while orders are being published. Observe ISR drop. Verify no
   messages are lost. Watch kafka-2 rejoin the ISR when restarted.

3. Send an order for $15,000. Observe the Payment Service transaction abort.
   Verify the offset was NOT committed. Verify the order is re-read on the next poll.

4. Start a second inventory-service instance. Watch partition reassignment in
   Control Center. Observe lag drop as both instances share the load.

5. Send 5 payment events for the same orderId with statuses PENDING → AUTHORISED → CAPTURED.
   After compaction, seek to the beginning of the payments topic. Only CAPTURED should remain.

6. Stop kafka-3. Place 10 orders (ISR=2, still available). Start a consumer that
   seeks to earliest on the orders topic. All 10 messages are present — verify
   no silent data loss despite broker failure.

---

## Notes for Claude Code

- Read CLAUDE.md at every session start — it contains authoritative topology
  and non-negotiable rules that override any simplification you might consider.
- The Payment Service MUST use the Kafka transactional API, not just idempotency.
  This is the key teaching moment for Phase 4.
- isolation.level=read_committed is REQUIRED on the Payment consumer. If you
  forget this, the exactly-once guarantee is broken silently.
- topic declarations use NewTopic beans — do NOT use auto-create or rely on
  Kafka's default topic settings.
- Each phase should be fully working before the next begins.
