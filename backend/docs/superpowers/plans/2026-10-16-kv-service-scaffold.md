# KV Service Scaffold Implementation Plan

> **For agentic workers:** Execute this plan inline, one task at a time. Keep the user's existing `.idea/encodings.xml` change out of the feature commit.

**Goal:** Add the `xiaohashu-kv` Maven service hierarchy with API and runnable Biz modules, configured for port 8084 and Nacos discovery.

**Architecture:** Add a root aggregator `xiaohashu-kv` with `xiaohashu-kv-api` and `xiaohashu-kv-biz` children. The API module depends on `xiaoha-common`; Biz is a Spring Boot web application and registers as `xiaohashu-kv` in Nacos namespace `xiaohashu`.

**Tech Stack:** Java 17, Maven, Spring Boot 3.0.2, Spring Cloud Alibaba Nacos Discovery.

**Spec:** User tutorial attachment `C:\Users\qiufengm\.codex\attachments\d3742cc1-2136-4fb1-97a9-6e0ccb5d672d\已粘贴的文本.txt`.

## Global Constraints

- Keep service port `8084` and service name `xiaohashu-kv` as specified by the tutorial.
- Do not add Cassandra repositories or KV endpoints in this scaffold chapter.
- Do not include `.idea/encodings.xml` or local credentials in the chapter commit.
- Nacos is currently stopped and its logs report `No DataSource set`; do not alter or recreate that container as part of this chapter.
- Do not use recursive/bulk deletion commands; the tutorial's IDE-generated source cleanup is not needed in this repository.

---

### Task 1: Add the Maven module hierarchy

**Files:**
- Modify: `pom.xml`
- Create: `xiaohashu-kv/pom.xml`
- Create: `xiaohashu-kv/xiaohashu-kv-api/pom.xml`
- Create: `xiaohashu-kv/xiaohashu-kv-biz/pom.xml`

- [x] Add `xiaohashu-kv` to the root `<modules>` list.
- [x] Make the parent artifact a `pom` aggregator with API and Biz child modules.
- [x] Configure API as a JAR depending on `xiaoha-common`.
- [x] Configure Biz as a JAR with `xiaoha-common`, Spring Web, Spring Boot Test, Spring Cloud Bootstrap, and Nacos Discovery dependencies; enable Spring Boot repackage.
- [x] Run `mvn -pl xiaohashu-kv -am validate`.

### Task 2: Add the runnable service and configuration

**Files:**
- Create: `xiaohashu-kv/xiaohashu-kv-biz/src/main/java/com/quanxiaoha/xiaohashu/kv/biz/XiaohashuKVBizApplication.java`
- Create: `xiaohashu-kv/xiaohashu-kv-biz/src/main/resources/application.yml`
- Create: `xiaohashu-kv/xiaohashu-kv-biz/src/main/resources/bootstrap.yml`
- Create: `xiaohashu-kv/xiaohashu-kv-biz/src/main/resources/logback-spring.xml`

- [x] Add a `@SpringBootApplication` entry point named `XiaohashuKVBizApplication`.
- [x] Configure `server.port: 8084` and active profile `dev`.
- [x] Configure bootstrap discovery name `xiaohashu-kv`, group `DEFAULT_GROUP`, namespace `xiaohashu`, and server address `127.0.0.1:8848`.
- [x] Follow the existing service Logback configuration and set `appName` to `kv`.

### Task 3: Verify, review, and commit

- [x] Run `mvn -pl xiaohashu-kv/xiaohashu-kv-api,xiaohashu-kv/xiaohashu-kv-biz -am package` and inspect the full reactor result.
- [x] Smoke-test Spring Boot with discovery disabled for this run; Nacos registration remains unverified because the local Nacos container is stopped.
- [x] Review the diff and ensure only the KV scaffold plus this plan are staged.
- [x] Commit this chapter as `feat(kv): scaffold key-value service`.
