# 用户资料修改接口设计

## 背景与目标

按教程 9.3 将用户资料修改能力从认证服务落到独立用户服务。接口为 `POST /user/update`，接收 `multipart/form-data`，支持昵称、小哈书号、性别、生日、个人简介、头像和背景图字段。当前章节先实现文本字段入库；头像和背景图接收请求，但文件上传留给后续 Feign 小节。

## 方案

### 共享框架组件

- 在 `xiaoha-framework` 增加 `xiaoha-spring-boot-starter-biz-context`，提供 `LoginUserContextHolder` 和 Servlet 请求过滤器。过滤器从 `userId` 请求头设置上下文，并在请求完成后清理；用户服务业务层通过 holder 获取当前用户 ID。
- 增加 `xiaoha-spring-boot-starter-jackson`，将认证服务现有的日期序列化配置抽到可复用的自动配置中。认证服务保留自己的既有配置，避免本节无关迁移。
- 在 `xiaoha-common` 增加 `ParamUtils`，集中校验昵称、小哈书号和个人简介长度。

### 用户服务

- 增加 `SexEnum` 和用户资料校验错误码。
- 增加表单 VO、`UserService`、实现类与 `/user/update` 控制器。业务层只更新非空文本/日期/性别字段，设置更新时间后调用 `updateByPrimaryKeySelective`；没有可更新字段时不访问数据库。
- 文件字段本节不上传、不写入数据库，保留后续接入 OSS 的位置。
- 不在 multipart 控制器上使用 `@ApiOperationLog`，避免日志切面对文件流参数做 Jackson 序列化。

### 认证与网关

- 移除认证服务 `UserController` 的类级 `/user` 前缀；方法级路径保持不变。
- 相应调整网关登录白名单和登出权限匹配，从 `/auth/user/...` 改为 `/auth/...`。
- 增加 `lb://xiaohashu-user` 路由，匹配 `/user/**` 并剥离首段。对外资料修改地址为 `/user/user/update`，转发到用户服务的 `/user/update`。

## 验证范围

- 参数校验覆盖边界长度、禁用字符、合法/非法小哈书号及个人简介长度。
- 用户服务测试覆盖字段更新、无更新字段不写库、无效字段不写库，以及 multipart 路由绑定。
- Maven 构建覆盖用户服务、认证服务、网关和框架模块。
- 不依赖本地 MySQL 写入或运行中的 Nacos；若服务发现环境不可用，会单独说明实际联调范围。

## 接受标准

1. `/user/update` 接收 `multipart/form-data`，并将有效资料字段写入当前用户记录。
2. 参数错误返回现有统一响应格式、`PARAM_NOT_VALID` 错误码和对应字段校验信息。
3. 网关按 `/user/**` 将请求转到用户服务，认证服务旧路径调整后仍由 `/auth/**` 路由访问。
4. 上述自动化验证通过，并为本节生成单独的 Git 提交。
