# Local Xiaohashu Compose application

Goal: run the existing Java backend and its infrastructure as one `xiaohashu` Docker Desktop application, preserving the current databases and making the actual API entry point available locally.

The user authorized container packaging and local execution. The user subsequently supplied the original frontend at `F:\code\xiaohashu\xiaohashu-vue3`; build that Vue application into an Nginx image and serve it on port 8088. Do not describe an operations dashboard as the product UI.

## Constraints and design

- Keep root `.evn` unread and unchanged. Keep user changes to `.idea/encodings.xml`, `AGENTS.md`, and `HANDOFF-CODEX.md` out of commits.
- Never delete files or data volumes in bulk. Reuse persistent volumes safely when switching to Compose. The user later authorized removing stopped legacy Xiaohashu containers; preserve their named volumes and host data directories.
- Build the twelve executable modules with `D:\java\jdk-17.0.18+8` and package each as `xiaohashu/<service>:local` using the installed Temurin 17 runtime image.
- Mount deployment-only configuration: Docker DNS names replace loopback addresses; Nacos discovery remains enabled, while remote development configuration is disabled to prevent old addresses overriding deployment settings.
- Leaf uses its own classpath properties, so put generated runtime `leaf.properties` before the boot JAR on the classpath. Do not rewrite tutorial source for deployment.
- Reuse the existing MySQL, Cassandra, Redis, MinIO, ZooKeeper and Elasticsearch data. Import required local infrastructure credentials from existing container metadata without printing them. Store generated deployment settings under ignored `infra/.runtime/`.
- Use local MinIO instead of requiring Aliyun credentials. Keep FRP optional; the local product API does not require a VPS.
- Docker has about 8 GiB allocated. Apply small JVM heaps and pools for local execution; put optional dashboards on a Compose profile if the measured memory budget requires it.
- Report known tutorial gaps accurately: additional reconciliation jobs, 32-bit Roaring offsets versus Snowflake IDs, Sentinel demonstration fault injection, comment-cache defects, asynchronous note-content visibility, and unavailable public FRP validation.
- Add four read-only adapters under the Docker profile for the original frontend's channel/topic/discovery/profile contracts using existing MySQL data. Preserve string Snowflake IDs in cards. Keep teaching fault injection enabled by default in dev and disable it through a deployment setting.
- The integration check reproduced missing user IDs after Gateway service discovery switches threads. Under the Docker profile, replace the teaching header filter with an explicit request-context binding; preserve the original filter for other profiles. Provide a local Redis OTP helper so the original frontend can be used without SMS cloud credentials.

## Execution

- [x] Add shared boot-image Dockerfile, Docker context exclusions and explicit-JDK build script; compile/package all modules and build twelve local images. Build the supplied Vue frontend into a thirteenth local application image.
- [x] Add container application configuration and complete Compose service definitions, dependency health checks, memory settings and local-start orchestration.
- [x] Validate Redis supports both Bloom and Roaring before reusing the AOF; resolve deployment compatibility without deleting existing data.
- [x] Stop conflicting legacy infrastructure containers individually and start their Compose counterparts using existing data.
- [x] Start all backend services, inspect readiness, registrations and actual endpoint responses. Diagnose deployment failures before changing configuration.
- [x] Verify an authenticated gateway flow and representative read/write functionality where the tutorial implementation permits it, using random test data and cleaning it afterwards.
- [x] Document working URLs, startup/stop commands, measured limitations and tutorial completion gaps. Review the specific deployment files for a separate commit after verification.
