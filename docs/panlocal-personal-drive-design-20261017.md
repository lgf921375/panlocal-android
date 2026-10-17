# PanLocal 个人网盘授权下载模式设计（2026-10-17）

状态：**设计完成 / 尚未修改 APK / 未获用户账号授权 / 未进行本人文件 API 实测**。此文档是 V0.7 后续方案，不是运行验收结论。

## 0. 已核实的现状

- PanLocal 当前版本为 V0.7（`app/build.gradle`），部署方式是安卓本机 APK，不依赖独立服务器。
- 用户拥有原始约 2270 MiB 视频文件，分享链接属于同一百度网盘账号。
- 来自用户实机诊断：`share/verify errno=0`；`share/list errno=0`，文件数 1；`share/tplconfig` 返回 sign/timestamp；`sharedownload HTTP 200 errno=0`，但 `list=string`（长度约 4140），无有效 `dlink`。
- 分享接口返回字符串 **不能自动认定** 为文件超限的唯一原因。不得反复调整已通过环节来制造“通过”的假象。
- 现有 V0.7 的百度 APP 内 WebView 登录 Cookie 不是开放平台的 OAuth `access_token`。不可混用。

## 1. 设计目标（用户要求）

新增两种独立入口：

- “分享链接”：保留 V0.7 已通过的分享解析及 TXT 本地诊断，行为不倒退。
- “我的网盘”：用户在手机本地授权本人百度网盘，搜索或浏览其原始文件，取 `fs_id`，使用 **个人文件** API 请求 `dlink`，再验证该链接是否可下载。

明确：不是“绕过网盘限速”，是对已授权的个人文件按开放平台权限访问。2.27 GiB 下载、速度、有效期和分段下载均要实测。用户无需上传原始文件或分享账号凭据。

## 2. 关键 API 依据和当前不确定性

1. 百度账号 OAuth 官方说明：`https://openapi.baidu.com/oauth/2.0/authorize` 可以发起授权。官方网页授权的 `response_type=code` 方案，用 `code` 换令牌需要 `client_secret`；`redirect_uri=oob` 是其文档描述的无 Web Server 可选形式。
2. 百度开发者中心网盘接入示例中曾使用 `scope=basic,netdisk` 和 `response_type=token`、`redirect_uri=oob`。该示例较旧，**尚未核实当前 App Key 是否支持**；不要默认打开就能成功。
3. 本人文件列表和搜索的历史文档接口：
   - `GET https://pan.baidu.com/rest/2.0/xpan/file?method=list`（授权凭据）
   - `GET https://pan.baidu.com/rest/2.0/xpan/file?method=search&key=...`（授权凭据）
   - `GET https://pan.baidu.com/rest/2.0/xpan/nas?method=uinfo`（确认令牌和账号）
   - `GET https://pan.baidu.com/rest/2.0/xpan/multimedia?method=filemetas&fsids=[...]&dlink=1`（读取本人文件下载信息）
4. 文档新旧版本、开放平台应用类型、授权的 **scope 和目录可见范围** 可能不同；在完整访问测试通过前，不承诺能读取任意目录。个人账号已登录百度官方 APP 不等于 OAuth 成功。
5. 下载地址可能需要与令牌关联，并在访问时携带官方要求的请求头、校验 200/206/403/重定向、有效期及 Range；以当前接口文档及真实响应为准，不猜测。

主要证据 URL：
- https://openauth.baidu.com/doc/doc.html
- https://developer.baidu.com/question/detail.html?id=177
- https://developer.baidu.com/question/detail.html?id=178
- https://developer.baidu.com/question/detail.html?id=179
- https://developer.baidu.com/question/detail.html?id=224
- https://github.com/baidu-netdisk/mcp/blob/main/README.md （注意新平台接入资格可能变化）

## 3. 授权架构决策

### 不可做

- **不将 Secret Key、Access Token、Refresh Token、Cookie 放入 GitHub、APK 常量、日志、崩溃信息或下载地址展示文本。**
- 不用 App Key 自动伪造“已登录”状态。
- 不要求用户在聊天中提供 Secret Key、Access Token、Refresh Token 或 Cookie。
- 不将用户账号凭证转发到开发者的任何服务器，不自动上传 TXT 日志。

### 方案 A — 无服务器的手机本地试验（首选先验证）

仅在应用当前所属 OAuth 配置 **确认支持免客户端密钥签发用户访问令牌** 时启用。示例思路为外部安全浏览器打开 `openapi.baidu.com` 官方授权页面，以受支持的回调方式领取用户令牌，在 APP 本机保存并经网盘 uinfo 验证。旧资料出现 `response_type=token` 和 `redirect_uri=oob`；是否仍有效必须先验证，不能将其直接视为生产级安全方案。

- App Key 由用户 **在手机 APP 设置中填写**，只用于构造 `client_id`，不需要提交至公共仓库。
- 默认 **不接收 Secret Key 输入**。
- 只允许 OpenAPI 官方 HTTPS 授权域及 pan.baidu.com 接口域，显示当前授权域供用户核验。
- 授权失败或被要求 `client_secret`，立即停止并报告“不支持无服务端方案”，不得把秘密塞进 APK 继续。
- 令牌经 Android Keystore 保护后存于本机私有存储；增加断开授权、清理本机令牌、过期重授权。令牌不写入诊断记录。
- 如果提供令牌粘贴框，必须显著标注“仅贴在手机 APP 内，不要在聊天中发送”，不将其复制/显示进公开日志。

### 方案 B — 授权码 / 刷新令牌流程（需密钥安全条件）

官方网页授权文档中 `authorization_code` 和 `refresh_token` 的交换需要 `client_secret`。纯 APK 公开分发不能安全保管它；在未证实支持 PKCE 或其他安全免密流程前，**不得私自把 Secret Key 固化进安卓客户端**。如果当前账号的 App Key 仅支持此模式，先向用户报告无法在“纯本地且无后端、无泄露密钥”条件下完成安全自动续期，并让用户决定更换受支持的授权形式或调整部署约束。

## 4. APP 模块与界面

在现有单 Activity 项目上增量添加；不重构分享接口。

- `MyDriveSettings`：输入 App Key；只显示脱敏预览；显示账号授权状态、scope、过期时间；断开授权。
- `OAuthFlow`：官方授权链接、回调/手动领取方式验证、`state` 防 CSRF、超时与取消；不接触密码页面内容。
- `TokenVault`：Android Keystore 加密存储令牌；内存中短期使用；日志只显示 tokenPresent boolean。
- `MyDriveApi`：uinfo → list/search → filemetas（dlink=1）；服务端错误分类；只读。
- `MyDriveUI`：“我的网盘”入口；文件搜索、浏览；结果中显示原始文件路径、文件大小和 fs_id（仅界面，不进默认诊断 TXT）；请求下载链接按钮；复制下载地址与下载前小流量检查分开。
- `DiagnosticLog`：扩展 OAuth 阶段（不含 code、token）、uinfo errno、搜索结果个数、filemetas errno、dlinkPresent、HTTP 状态及重定向类别；继续允许完整 TXT 手动导出。

文件定位不能仅凭“文件名和大小”，同名文件需用户在 APP 内确认完整路径及大小才可以调用文件元信息 API。跨账号与跨权限区域的 `fs_id` 不保证可复用。

## 5. 实施和验收关口

| 阶段 | 交付 | 验收状态 |
|---|---|---|
| P0 | 确认 App Key 所属应用类型、有效权限(scope)、可用授权方式与 callback 设置 | **待确认** |
| P1 | APP 配置 App Key + OAuth 接入 + 安全保管 | **待开发** |
| P2 | 经 `uinfo` 校验用户授权成功，能够说明账号身份（不输出敏感号） | **待实测** |
| P3 | 搜索/浏览用户原始文件，核对路径、大小、fs_id | **待实测** |
| P4 | 文件元信息返回 dlink | **待实测** |
| P5 | 最小请求验证 dlink 有效（权限、状态码、响应头）；不直接下载 2.27 GiB | **待实测** |
| P6 | 用户许可后验证大文件下载可行性、Range/断点续传与完整性 | **待实测** |

每关独立标注“通过 / 失败 / 未覆盖”。GitHub Actions 编译通过不代表接口运行通过；APP 必须由手机实际操作确认。若授权失败先修授权；不要回头重做 V0.7 已通过的分享列表流程。未经 P0 验证不应承诺新 APK 能下载本人文件。

## 6. 唯一阻塞问题

用户需到开发者控制台确认：**App Key 所属应用类型及该应用当前被允许的 OAuth 授权方式 / 回调地址设置**。只需文字描述或遮挡密钥的页面截图；不应提供 Secret Key、Access Token、Refresh Token、Cookie。依据此信息从方案 A / B 决策，再进行首次 APK 实现。

## 7. 2026-10-17 用户控制台截图核查（安全脱敏）

- 【已确认】开发者应用显示“已上线、仅限个人场景使用”，应用类型为“软件”，应用分类为“其他”；已经配置 HTTPS OAuth 回调。
- 【待确认】回调地址的域名是否由用户自己控制；在确认之前，不能启动授权测试，尤其不能将授权码发送到该回调地址。
- 【安全提醒】截图显示了保密密钥的明文。应在官方控制台轮换 SecretKey 和 SignKey 等已暴露秘密；新值不进入 GitHub、聊天日志或 APK 常量。
- 【官方文档】百度帐号 OAuth 授权码流程可以在无 Web Server 时使用 redirect_uri=oob 显示授权码，但 code 换 token 仍需要 client_secret。来源：https://openauth.baidu.com/doc/doc.html
- 【历史资料，未验证】2021 年百度开发者中心问答展示过 response_type=token + redirect_uri=oob，当前账号、当前应用类型下是否仍可用尚未实测。来源：https://developer.baidu.com/question/detail.html?id=177
- 【当前决定】现有 V0.7 APK 暂不修改；先完成回调所有权确认和密钥轮换，再决定安卓本地授权实现，不上传敏感信息。

## 8. 2026-10-17 回调域名确认及授权架构约束

- 【用户确认】`899yx.online` 为用户管理的域名，绑定其新加坡服务器。
- 【未确认】`https://899yx.online/api/baidu/callback` 已有可工作的 HTTPS OAuth 回调处理程序；外部检索无法访问该 URL，不能据此判定服务端坏了。
- 【官方 OAuth 文档】百度 2026-07 更新的授权码流程中，`response_type=code` 回调提供 `code` 和 `state`，服务器需要 `client_secret` 交换 Access Token，刷新 Access Token 也需要 `client_secret`。资料：https://openauth.baidu.com/doc/doc.html。
- 【架构方案】最小服务端 OAuth 回调：仅承担授权开始/回调/换取与刷新凭证的安全工作，不托管安卓 APP 的网页前端。PanLocal 继续直接安装手机，文件搜索和下载功能独立实现；具体凭证返回方式需评估安全，不得在浏览器查询参数中传输完整令牌。
- 【安全】百度后台截图公开了 SecretKey、SignKey，应先轮换已曝光密钥。新密钥只能由用户安全配置在服务器，不能提交公开仓库、截图或写入 APK。
- 【实施边界】用户此前明确不部署网站，因此在得到用户同意使用现有服务器承载**仅 OAuth 的小服务**之前，不能进行服务端部署、更改服务或重启。不得影响现有项目。
- 【未验证】服务器回调路由可用性、应用权限 scope、官方 App Key 的令牌实际授予情况、原始文件搜索与大文件 dlink 下载。
