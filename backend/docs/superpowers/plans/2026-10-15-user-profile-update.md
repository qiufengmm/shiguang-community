# 用户资料修改接口 Implementation Plan

> **For agentic workers:** Use the project’s available execution workflow task-by-task. Keep changes for this chapter together in one chapter commit.

**Goal:** 按教程 9.3 在用户服务实现 multipart 用户资料更新，并使登录用户上下文、校验、认证路径和网关路由可用。

**Architecture:** 在框架层增加上下文和 Jackson starter，在 common 增加参数校验工具；用户服务通过共享 holder 获取 `userId`，验证可更新字段后执行一次 MyBatis selective update。Gateway 继续负责认证并透传 `userId`，OSS 文件上传留给下一小节。

**Tech Stack:** Java 17、Spring Boot 3、Spring Cloud Gateway、MyBatis、JUnit 5、Mockito、MockMvc。

**Spec:** `docs/superpowers/specs/2026-10-15-user-profile-update-design.md`

## Global Constraints

- 文件接口接收 `multipart/form-data`。
- 图片字段本节只接收，不调用 OSS。
- 对空更新请求不执行数据库更新。
- 不把 `.idea/encodings.xml` 纳入章节提交。
- 每个章节完成验证后只生成一个章节提交；不推送远端。

---

### Task 1: 参数校验和性别枚举

**Files:**
- Create: `xiaoha-framework/xiaoha-common/src/main/java/com/quanxiaoha/framework/common/util/ParamUtils.java`
- Test: `xiaoha-framework/xiaoha-common/src/test/java/com/quanxiaoha/framework/common/util/ParamUtilsTest.java`
- Create: `xiaohashu-user/xiaohashu-user-biz/src/main/java/com/quanxiaoha/xiaohashu/user/biz/enums/SexEnum.java`
- Test: `xiaohashu-user/xiaohashu-user-biz/src/test/java/com/quanxiaoha/xiaohashu/user/biz/enums/SexEnumTest.java`

- [ ] **Step 1: Write failing validation tests**

Test nickname accepts lengths 2 and 24, rejects lengths 1 and 25, and rejects `@`; test xiaohashu ID accepts `user_01` and rejects punctuation or lengths outside 6–15; test introduction accepts 1 and 100 characters and rejects empty or 101 characters. Test `SexEnum.isValid(0/1)` as true and `isValid(2/null)` as false.

- [ ] **Step 2: Run the focused tests and confirm they fail because the classes are absent**

Run: `mvn -pl xiaohashu-user/xiaohashu-user-biz -am -Dtest=ParamUtilsTest,SexEnumTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Implement `ParamUtils` and `SexEnum`**

Use the tutorial ranges and regexes: nickname 2–24 without `[!@#$%^&*(),.?\":{}|<>]`; ID 6–15 matching `^[a-zA-Z0-9_]+$`; introduction non-empty and at most 100 characters. Null values return false.

- [ ] **Step 4: Re-run focused tests and confirm they pass**

Run the same focused Maven command.

### Task 2: Shared context and Jackson starters

**Files:**
- Modify: `pom.xml`, `xiaoha-framework/pom.xml`
- Create: `xiaoha-framework/xiaoha-spring-boot-starter-biz-context/pom.xml`
- Create: `xiaoha-framework/xiaoha-spring-boot-starter-biz-context/src/main/java/com/quanxiaoha/framework/biz/context/holder/LoginUserContextHolder.java`
- Create: `xiaoha-framework/xiaoha-spring-boot-starter-biz-context/src/main/java/com/quanxiaoha/framework/biz/context/filter/HeaderUserId2ContextFilter.java`
- Create: `xiaoha-framework/xiaoha-spring-boot-starter-biz-context/src/main/java/com/quanxiaoha/framework/biz/context/config/BizContextAutoConfiguration.java`
- Create: `xiaoha-framework/xiaoha-spring-boot-starter-biz-context/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `xiaoha-framework/xiaoha-spring-boot-starter-biz-context/src/test/java/com/quanxiaoha/framework/biz/context/filter/HeaderUserId2ContextFilterTest.java`
- Create: `xiaoha-framework/xiaoha-spring-boot-starter-jackson/pom.xml`
- Create: `xiaoha-framework/xiaoha-spring-boot-starter-jackson/src/main/java/com/quanxiaoha/framework/jackson/config/JacksonAutoConfiguration.java`
- Create: `xiaoha-framework/xiaoha-spring-boot-starter-jackson/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `xiaoha-framework/xiaoha-spring-boot-starter-jackson/src/test/java/com/quanxiaoha/framework/jackson/config/JacksonAutoConfigurationTest.java`

- [ ] **Step 1: Write failing context-filter test**

Pass a mock servlet request with `userId: 42` through the filter; assert the holder returns `42` while the chain runs and is empty after the filter returns.

- [ ] **Step 2: Run the focused test and confirm it fails because the starter is absent**

Run: `mvn -pl xiaoha-framework/xiaoha-spring-boot-starter-biz-context -am -Dtest=HeaderUserId2ContextFilterTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Implement the context starter**

Use `TransmittableThreadLocal<Map<String,Object>>`, `GlobalConstants.USER_ID`, and a `OncePerRequestFilter` that removes the holder in `finally`. Register the filter through Boot 3 auto-configuration.

- [ ] **Step 4: Run the context-filter test and confirm it passes**

Run the same focused Maven command.

- [ ] **Step 5: Write failing Jackson configuration test**

Assert that a configured `ObjectMapper` serializes `LocalDate.of(2024, 4, 5)` as `"2024-04-05"` and deserializes that value back to the same date.

- [ ] **Step 6: Implement the Jackson starter and run the test**

Extract the existing auth date/time formats into an auto-configuration, use `@ConditionalOnMissingBean(ObjectMapper.class)`, and initialize `JsonUtils` with the configured mapper.

Run: `mvn -pl xiaoha-framework/xiaoha-spring-boot-starter-jackson -am -Dtest=JacksonAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`

### Task 3: User-service update behavior

**Files:**
- Modify: `pom.xml`, `xiaohashu-user/xiaohashu-user-biz/pom.xml`
- Modify: `xiaohashu-user/xiaohashu-user-biz/src/main/java/com/quanxiaoha/xiaohashu/user/biz/enums/ResponseCodeEnum.java`
- Create: `xiaohashu-user/xiaohashu-user-biz/src/main/java/com/quanxiaoha/xiaohashu/user/biz/model/vo/UpdateUserInfoReqVO.java`
- Create: `xiaohashu-user/xiaohashu-user-biz/src/main/java/com/quanxiaoha/xiaohashu/user/biz/service/UserService.java`
- Create: `xiaohashu-user/xiaohashu-user-biz/src/main/java/com/quanxiaoha/xiaohashu/user/biz/service/impl/UserServiceImpl.java`
- Test: `xiaohashu-user/xiaohashu-user-biz/src/test/java/com/quanxiaoha/xiaohashu/user/biz/service/impl/UserServiceImplTest.java`

- [ ] **Step 1: Write failing service tests**

Set context ID to `42`; verify a valid nickname update invokes `updateByPrimaryKeySelective` once with ID 42 and that nickname. Verify empty VO and file-only VO do not invoke the mapper. Verify invalid nickname throws `IllegalArgumentException` and does not invoke the mapper.

- [ ] **Step 2: Run the focused test and confirm it fails because the service classes are absent**

Run: `mvn -pl xiaohashu-user/xiaohashu-user-biz -am -Dtest=UserServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Implement the VO, service, error codes and dependencies**

VO fields are `MultipartFile avatar`, `String nickname`, `String xiaohashuId`, `Integer sex`, `LocalDate birthday`, `String introduction`, and `MultipartFile backgroundImg`. Validate only non-blank strings and non-null scalar values. Set `updateTime` and call `updateByPrimaryKeySelective` once only if a field changed. Leave both file uploads unimplemented in this section.

- [ ] **Step 4: Run the focused service tests and confirm they pass**

Run the same focused Maven command.

### Task 4: Multipart controller and path migration

**Files:**
- Create: `xiaohashu-user/xiaohashu-user-biz/src/main/java/com/quanxiaoha/xiaohashu/user/biz/controller/UserController.java`
- Test: `xiaohashu-user/xiaohashu-user-biz/src/test/java/com/quanxiaoha/xiaohashu/user/biz/controller/UserControllerTest.java`
- Modify: `xiaohashu-auth/src/main/java/com/quanxiaoha/xiaohashu/auth/controller/UserController.java`
- Modify: `xiaohashu-gateway/src/main/java/com/quanxiaoha/xiaohashu/gateway/auth/SaTokenConfigure.java`
- Modify: `xiaohashu-gateway/src/main/resources/application.yml`

- [ ] **Step 1: Write failing MockMvc test for multipart `/user/update`**

Submit multipart fields `nickname=Alice`, `sex=1`, and `birthday=2000-01-02`; assert HTTP 200 and that the service receives the corresponding VO values.

- [ ] **Step 2: Run the focused controller test and confirm it fails because the controller is absent**

Run: `mvn -pl xiaohashu-user/xiaohashu-user-biz -am -Dtest=UserControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Implement controller and update route paths**

Map `POST /user/update` with `consumes = multipart/form-data`; do not add the operation-log aspect. Remove the auth controller class-level `/user` mapping, change gateway login/logout matches to `/auth/login` and `/auth/logout`, and add the `xiaohashu-user` route with `Path=/user/**` and `StripPrefix=1`.

- [ ] **Step 4: Run the focused controller test**

Run the same focused Maven command.

### Task 5: Chapter verification and commit

**Files:** all files listed in Tasks 1–4.

- [ ] **Step 1: Run the user-service package and tests**

Run: `mvn -pl xiaohashu-user/xiaohashu-user-biz -am package`

- [ ] **Step 2: Run auth and gateway package builds**

Run: `mvn -pl xiaohashu-auth,xiaohashu-gateway -am package`

- [ ] **Step 3: Review status and staged diff**

Confirm `.idea/encodings.xml` is not staged; stage only chapter 9.3 source, tests, framework modules, and this plan.

- [ ] **Step 4: Create the chapter commit**

Commit with message `feat(user): add profile update endpoint`.
