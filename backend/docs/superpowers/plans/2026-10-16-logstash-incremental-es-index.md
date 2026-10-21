# Logstash Incremental ES Index Implementation Plan

> For agentic workers: follow this plan inline in the current user-authorized checkout. Do not dispatch subagents.

**Goal:** Complete tutorial 20.11 by incrementally syncing changed notes and users from MySQL into their existing Elasticsearch indices.

**Architecture:** Keep the existing two Logstash JDBC pipelines and index mappings. Add timestamp tracking and a per-pipeline metadata file, filter rows by their source update time, and validate both inputs against the live Docker MySQL and Elasticsearch services.

**Tech Stack:** Logstash 7.3.0 on Windows, logstash-input-jdbc, MySQL 8 in Docker, Elasticsearch 7 in Docker.

**Spec:** `F:\code\xiaohashu\教程\20 Elasticsearch 分布式搜索引擎\20.11 增量 es 索引构建：使用 logstash-input-jdbc 插件.md`

## Global Constraints

- Follow tutorial SQL and JDBC settings; report any discovered risks without silently changing the design.
- Keep one `last_run_metadata_path` per JDBC input.
- Preserve the user's existing MySQL rows and Elasticsearch documents after verification.
- Never read, write, or stage the project-root `.evn`; preserve `.idea/encodings.xml`, `AGENTS.md`, and `HANDOFF-CODEX.md`.
- Use exact Git paths, commit only this section, and never push or merge.

---

### Task 1: Add note incremental tracking

**Files:**
- Modify: `infra/logstash/sync-es-note-index/jdbc.conf`
- Modify: `infra/logstash/sync-es-note-index/jdbc.sql`

**Interfaces:**
- The JDBC input tracks the returned `update_time` timestamp in the note pipeline's own `last_run_time` file.
- The SQL selects notes where the note or its creator has an `update_time` greater than `:sql_last_value`.

- [x] Add `jdbc_default_timezone`, `use_column_value`, `tracking_column`, `tracking_column_type`, `last_run_metadata_path`, and `clean_run` exactly as tutorial 20.11 specifies.
- [x] Add the tutorial's parenthesized note-or-user timestamp predicate to the note SQL.
- [x] Preserve existing note fields, filters, index name, and document ID.

### Task 2: Complete the user-increment homework

**Files:**
- Modify: `infra/logstash/sync-es-user-index/jdbc.conf`
- Modify: `infra/logstash/sync-es-user-index/jdbc.sql`

**Interfaces:**
- The user JDBC input tracks its own returned `update_time` in a separate `last_run_time` file.
- User SQL selects active, undeleted users whose `update_time` exceeds `:sql_last_value`.

- [x] Apply the same tutorial timestamp-tracking settings with the user pipeline's own metadata path.
- [x] Select formatted `u.update_time AS update_time` and filter with `u.update_time > :sql_last_value`.
- [x] Preserve existing user fields, filters, index name, and document ID.

### Task 3: Validate and run both pipelines

**Files:**
- Create: `docs/tutorial/20.11-logstash-incremental-index.md`
- Modify: `docs/superpowers/plans/2026-10-16-logstash-incremental-es-index.md`

- [x] Compare the active Logstash configuration copies with the repository copies; copy only the four reviewed files to `E:\java_workspace\logstash-7.3.0\config\mysql\`.
- [x] Run Logstash `--config.test_and_exit` for both pipelines using the installed JDK 8 and JDBC plugin; both report `Configuration OK`.
- [x] Let each previously absent metadata file initialize from its first live JDBC query; the note query initially returned zero changed rows and the user query loaded two active users.
- [x] For the note pipeline, snapshot one existing note's MySQL timestamp and complete ES document, assign a randomized timestamp greater than the 8-hour-adjusted checkpoint, confirm the next JDBC query returns one row and Elasticsearch updates it, stop the pipeline, restore the MySQL timestamp and original ES document, and reset metadata to the pre-test checkpoint.
- [x] Repeat the controlled update, observation, stop, and exact restoration for one active user with the user pipeline; its first query returned two rows and its incremental query returned one.
- [x] Confirm source counts (3 notes, 2 active users) match ES counts; both Logstash pipelines are stopped.
- [x] Record actual verification and tutorial risks in `docs/tutorial/20.11-logstash-incremental-index.md`.
- [x] Review `git diff --check` and the exact staged file list; commit as `feat(search): sync note and user indexes incrementally` with a Chinese 20.11 body.
