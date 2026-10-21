# Xiaohashu Reliability Hardening Implementation Plan

> **For agentic workers:** Implement the tasks in order by subsystem. Each task uses the reviewed design document linked below and ends with targeted verification before moving on.

**Goal:** Apply the four reviewed reliability designs to the existing Xiaohashu local deployment and services, while keeping unsafe live reconciliation in audit-only mode until a safe write watermark exists.

**Architecture:** Keep MySQL as the authoritative relational store, Cassandra as note/comment body storage, Redis as cache and relation acceleration, RocketMQ as at-least-once transport, and Canal/Elasticsearch as an eventually consistent search projection. Persist events before acknowledging work where the reviewed design requires it. Keep LAN media reads behind same-origin Nginx and leave public FRP verification deferred.

**Tech Stack:** Spring Boot 3.0.2, Java 17, MyBatis, MySQL 8, Cassandra 5, Redis 8, RocketMQ, Canal, Elasticsearch 7, Docker Compose, PowerShell.

**Specs:**
- `docs/superpowers/specs/2026-10-18-docker-local-stack-reliability-design.md`
- `docs/superpowers/specs/2026-10-18-note-content-and-snowflake-id-design.md`
- `docs/superpowers/specs/2026-10-18-comment-delivery-and-cache-design.md`
- `docs/superpowers/specs/2026-10-18-count-search-consistency-design.md`

## Global Constraints

- Preserve `.idea/encodings.xml`, `AGENTS.md`, `HANDOFF-CODEX.md`, and the root `.evn`; never stage protected files or inspect the root `.evn`.
- Never run recursive or bulk deletion commands; remove at most one explicitly identified file per command.
- Stage exact paths only; never push or merge.
- Use JDK `D:\java\jdk-17.0.18+8`; do not run Maven offline.
- Test data must be random and cleaned up; only stop processes started by this work.
- Public FRP/VPS access remains deferred.
- Count repair remains read-only audit unless source-event watermarks or a verified maintenance write window make overwrite safe.

---

### Task 1: Make the local Docker stack portable, LAN-safe, and recoverable

**Files:**
- Modify `infra/compose.yaml`, `infra/compose.app.yaml`, `infra/README.md`
- Modify local setup/start/backup scripts under `infra/scripts/`
- Modify app Nginx configuration under `infra/nginx/`
- Modify note-side local media proxy/controller only if same-origin media routing needs a service endpoint
- Add focused script/config checks under `infra/tests/` if the repository has an established test harness

**Deliverable:** Parameterized host paths and named volumes, safe reuse-vs-fresh bootstrap, loopback infrastructure exposure with only UI port 8088 available to LAN, same-origin read-only MinIO media proxy, pinned Redis image digest, complete per-product backup manifest and isolated restore instructions. Existing data mappings must remain unchanged by default.

**Verification:** Validate Compose rendering and health, ensure all infrastructure ports bind to loopback, ensure UI binds to LAN, request homepage/API/media through localhost and LAN address where a second device is available, run backup against disposable/random test data, and prove restore targets isolated volumes.

### Task 2: Make note publication and note-ID acceleration correct

**Files:**
- Modify `xiaohashu-note/xiaohashu-note-biz/src/main/java/com/quanxiaoha/xiaohashu/note/biz/listener/PublishNote2DBLocalTransactionListener.java`
- Modify note detail caching and publish flow in `xiaohashu-note/xiaohashu-note-biz/src/main/java/com/quanxiaoha/xiaohashu/note/biz/service/impl/NoteServiceImpl.java`
- Modify note content consumer behavior in `xiaohashu-kv/xiaohashu-kv-biz/src/main/java/com/quanxiaoha/xiaohashu/kv/biz/consumer/SaveNoteContentConsumer.java`
- Modify note-ID bitmap/set Lua resources and relation cache code in `xiaohashu-note/xiaohashu-note-biz/`
- Add regression tests beside the existing note listener, note service, and Lua script tests

**Deliverable:** A successful text-note publish synchronously writes Cassandra before SQL commit; SQL unknown outcomes never delete potentially referenced content; delayed publish messages cannot overwrite edits; incomplete details are not cached; like/collect membership stores full Snowflake IDs as decimal strings with explicit empty-set state.

**Verification:** Tests for KV failure, SQL definite failure, SQL unknown outcome, delayed event vs edit/clear, null-body cache prevention, empty membership, IDs above 2^32 and 2^53, and repeated low-word IDs. Then compile and exercise note/KV services against Docker dependencies.

### Task 3: Correct comment detail cache and hot-comment ranking

**Files:**
- Modify `CommentServiceImpl.java` in `xiaohashu-note/xiaohashu-note-biz/`
- Modify comment mapper SQL and `update_hot_comments.lua` in the note module
- Modify `CommentHeatUpdateConsumer.java` in the note module
- Add focused cache, Lua, ranking, and pagination regression tests

**Deliverable:** Missing cache values are fetched by their exact IDs and never represented by durable empty placeholders; incomplete text details are not cached; hot cache updates preserve 500 entries, compare numeric heat, invalidate on decreases including zero, and use stable `heat DESC, commentId DESC` ordering.

**Verification:** Cover partial Caffeine/Redis hits, IDs-to-MGET alignment, missing Cassandra body, heat values 9.99/10.00, 499/500/501 items, heat decrease to zero, concurrent rebuild/update, ties across pages, and final Redis/SQL result agreement.

### Task 4: Persist comment delivery and consumer follow-up work

**Files:**
- Modify comment publishing in `CommentServiceImpl.java` and related MQ DTOs
- Add MySQL outbox/inbox/follow-up schema and MyBatis mapper/model/service files in the note module
- Modify `Comment2DBConsumer.java`, count consumers, and `KeyValueRpcService.java`
- Add tests for relay leasing, stable content UUID, per-event idempotence, durable follow-up and DLQ recovery

**Deliverable:** Accepted comments have committed outbox rows; relay retries survive restart and multiple instances; consumers persist stable UUID and idempotency state independent of deletable comment rows; metadata, body, and follow-up work can be recovered after crashes; no consumer acknowledges work held only in an in-memory buffer.

**Verification:** Exercise broker rejection/restart, publish-accepted/mark-failed duplicate delivery, duplicate-A-plus-new-B count, SQL commit followed by process interruption, tombstone replay, KV retry UUID stability, inbox recovery, and first-consumer-group offset behavior.

### Task 5: Complete count marker durability and safe reconciliation auditing

**Files:**
- Modify `xiaohashu-data-align/src/main/java/com/quanxiaoha/xiaohashu/data/align/job/`
- Modify `xiaohashu-data-align/src/main/java/com/quanxiaoha/xiaohashu/data/align/consumer/`
- Modify data-align mapper XML files in `xiaohashu-data-align/src/main/resources/mapper/`
- Correct `database/19.1-data-align-daily-tables.sql` only after checking deployed table definitions
- Add focused unit/integration coverage for marker SQL failure, physical shard assignment, and five missing metrics

**Deliverable:** Five missing metrics are computed from the reviewed source tables and correct `user_id`/`note_id` entities. All seven daily marker families use durable SQL uniqueness and propagate SQL failures. Single-executor scheduling covers every physical shard and older pending dates. Default scheduled action audits drift only; a maintenance-gated write repair requires a verified write pause and drained MQ.

**Verification:** Randomized per-metric fixtures, all three physical shards, SQL failure and Bloom false-positive simulation, repeatable audit, maintenance repair while writes and relevant consumers are paused, Redis TTL preservation, and marker retention on failed work.

### Task 6: Make search refresh ordered, complete, and retryable

**Files:**
- Modify Canal subscription in `xiaohashu-search/xiaohashu-search-biz/src/main/resources/application-dev.yml` and deploy override
- Modify `CanalClient.java`, `CanalSchedule.java`, `NoteServiceImpl.java`, `UserServiceImpl.java`
- Modify data-align `SearchRpcService.java` and relevant mapper/index conversion files
- Add tests for count-table events, delete propagation, ES bulk item failures, and same-entity refresh ordering

**Deliverable:** Canal consumes note, user, note-count, and user-count changes; a single entity refresh path serializes full-document rebuilds; deleted/unindexable source records delete ES documents; ES failures propagate so Canal retries; direct RPC callers inspect business responses.

**Verification:** Update user profile and each note counter, inspect resulting ES documents, delete/unindex a source row, inject a bulk item failure and confirm retry, and race an older refresh against a newer refresh to prove stale data cannot win.

---
