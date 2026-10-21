# Local Acceptance Gaps and Demo Data Plan

> **For agentic workers:** Execute this plan task-by-task. Each task has its own verification checkpoint.

**Goal:** Verify the remaining non-FRP reliability boundaries against the running local app and add the approved 100-note / approximately 1,000-comment demo dataset.

**Status:** Completed except shared-service outage injection/pressure tests and public FRP access. A two-instance Redis snapshot rebuild passed; user 100's note count was reconciled after deploying the outbox/idempotency protections and confirming the relevant RocketMQ queues had zero lag/inflight and no DLQ. RocketMQ/Cassandra/Canal clients passed isolated closed-endpoint-to-healthy-endpoint probes. No shared infrastructure service was interrupted.

**Architecture:** Use the existing service APIs for persistent demo content so MySQL, Cassandra, RocketMQ, Redis, and Elasticsearch follow their normal paths. Use read-only or uniquely keyed fixtures for verification, and do not disrupt the already-running shared Compose stack.

**Tech Stack:** Java 17, Spring Boot, MySQL, Cassandra, Redis, RocketMQ, Canal, Elasticsearch, Docker Compose, PowerShell.

**Specs:**
- `docs/superpowers/specs/2026-10-18-comment-delivery-and-cache-design.md`
- `docs/superpowers/specs/2026-10-18-note-content-and-snowflake-id-design.md`
- `docs/superpowers/specs/2026-10-18-count-search-consistency-design.md`
- `docs/superpowers/specs/2026-10-18-docker-local-stack-reliability-design.md`

## Global Constraints

- FRP public/VPS verification remains deferred.
- Never inspect or modify the project-root `.evn`; preserve `.idea/encodings.xml`, `AGENTS.md`, and `HANDOFF-CODEX.md` as unstaged/uncommitted.
- Never use recursive or bulk deletion; only delete a single explicitly identified path at a time.
- Do not stop or restart existing shared Docker containers for fault injection; use isolated fixtures or report the scenario as not safely injectable.
- Do not run automatic or multi-user count correction. A one-user note-publish reconciliation is allowed only after the transactional outbox/idempotency schema and updated note/count services are deployed, the shared per-user lock is used by writers/consumers, and RocketMQ main/retry queues have zero lag/inflight with no matching DLQ messages.
- Use exact Git paths, no push or merge; use Java 17 `D:\java\jdk-17.0.18+8` for Maven.
- Create 100 demo notes and approximately 1,000 comments only through application APIs; record their IDs in a local runtime manifest for later selective cleanup.

---

### Task 1: Reconfirm local runtime and count-audit coverage

**Files:**
- Read `docs/deployment/tutorial-status.md`
- Read current Compose health, MySQL count tables, and count-audit records
- Update `docs/deployment/local-verification.md` only with observed results

**Deliverable:** A timestamped baseline showing the running services, current user/note/comment totals, audit-only scope, and any unmarked entities found by read-only comparison. The initial 49-versus-52 note-count mismatch is now reconciled under the added per-user lock and verified zero-lag MQ watermark; other metrics remain audit-only.

**Verification:** Query MySQL read-only; compare the five supplemental count metrics against their source tables for the locally marked entities. Do not update stored counts.

### Task 2: Verify note body visibility and Canal-to-ES refresh

**Files:**
- Use `xiaohashu-note` and `xiaohashu-search` APIs
- Update `docs/deployment/local-verification.md`

**Deliverable:** One uniquely identified API-published note whose body is immediately readable from Cassandra-backed detail, and whose searchable document reflects a subsequent note-count change.

The local demo user's nickname was also temporarily changed and restored; both the ES document and user search endpoint reflected each value.

**Verification:** Poll detail, search results, and the indexed comment count by created note ID. The verification note was retained as the first entry in the approved demo dataset, with its ID recorded in the runtime manifest.

### Task 3: Verify hot-comment 499/500/501 boundaries

**Files:**
- Existing `HotCommentLuaScriptsTest.java` and Lua resources only if a failing case is reproduced
- Update `docs/deployment/local-verification.md`

**Deliverable:** Numeric ordering, stable ties, retention of exactly 500 entries, correct behavior when an existing member is updated at 500 entries, and a full dual-`CommentServiceImpl` snapshot rebuild against real Redis. A controlled concurrent run passed: one service instance performed the database rebuild, the other skipped it, and the final snapshot/ready state was valid. High-volume pressure testing remains deferred.

**Verification:** Exercise 499, 500, and 501 candidates plus an update and heat decrease; inspect the resulting Redis ZSET and remove each temporary key individually.

### Task 4: Verify comment delivery recovery without interrupting shared services

**Files:**
- Existing comment outbox/inbox tests and recovery documentation
- Update `docs/deployment/local-verification.md`

**Deliverable:** Unit evidence that outbox send failure schedules retry without marking SENT, and inbox KV RPC failure schedules retry without marking APPLIED or creating downstream state. Real client probes from an isolated closed port to the healthy RocketMQ/Cassandra/Canal endpoints passed; they do not stop shared services or prove behavior during an actual shared-service outage. Actual Broker/Cassandra/Canal outage and DLQ recovery tests remain deferred.

**Verification:** Prefer existing integration/unit hooks or a separately named isolated test fixture. If the environment cannot isolate a real broker/storage failure safely, record the exact untested boundary rather than altering shared container state.

### Task 5: Add the approved demo dataset

**Files:**
- Add a repeatable local seed runner under `infra/`
- Add a local manifest under ignored `infra/.runtime/`
- Update `docs/deployment/local-verification.md`

**Deliverable:** 100 clearly identifiable local demo notes and 991 comments, attributed to existing local users 3 and 100, with every created note/comment ID recorded for selective cleanup. The repeatable runner now persists attempt markers and IDs as each API write becomes durable.

**Verification:** Seed through note/comment application APIs with bounded pacing; verify feed pagination, note details/body, comment pages, counts, and search visibility. Do not directly insert business rows into MySQL or Cassandra.

### Task 6: Reconcile reported status

**Files:**
- `docs/deployment/tutorial-status.md`
- `docs/deployment/local-verification.md`

**Deliverable:** Mark each boundary as passed, failed-and-fixed, or still deferred, preserving FRP public verification as deferred and count repair as audit-only.

**Verification:** Cross-check the status against command/API output from Tasks 1–5 and keep the shared Compose health state intact.
