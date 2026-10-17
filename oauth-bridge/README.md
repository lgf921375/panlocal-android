# PanLocal V0.8 — 自有网盘 OAuth Bridge (prototype)

**状态：源码原型，尚未部署新加坡服务器，也未进行真实百度账号授权或 2.27GB 下载测试。**

用户拥有 \`899yx.online\` 域名并已将其绑定至自己的新加坡服务器，开发者后台当前回调地址为 \`https://899yx.online/api/baidu/callback\`。本模块仅用于该域名对应的、用户本人授权的网盘数据，禁止共享给其他用户使用。

## 组件

- \`oauth-bridge/server.js\`：Node.js 20+、无第三方依赖；只监听回环 \`127.0.0.1:19731\`（可配置）；用于处理 OAuth 授权回调、令牌交换、网盘搜索、文件信息请求。App Secret 与 access_token **不在 APK、公开 GitHub 或诊断日志中**。
- \`app/src/main/java/com/panlocal/tool/MyDriveActivity.java\`：Android V0.8 中的“我的网盘”入口，连接 \`https://899yx.online/api/panlocal/\`；启动官方浏览器授权，返回手机后检查授权状态、搜索本人文件、申请文件 \`dlink\`。SDK/APP 本身不存储百度 access_token，桥接会话令牌仅在 APP 内存中保存。
- 既有 V0.7 分享模式与 TXT 诊断入口不修改其接口逻辑。

## 安全前置条件（必须先完成）

1. **轮换泄露密钥**：之前开发者后台截图中 SecretKey/SignKey 明文可见。请通过百度后台轮换；只在服务器设置环境变量，绝不向聊天提供密钥。
2. **不要直接执行部署**：先只读检查服务器已有 Nginx/HTTPS/端口/服务，防止影响 98K、皓石传奇三或其他服务。未经明确确认与服务器权限，禁止擅自部署/重启服务。
3. **匹配回调**：百度开放平台应用的回调必须与 \`https://899yx.online/api/baidu/callback\` 完全一致；官方 OAuth 文档要求 code 约 10 分钟内单次使用。应用具体 scopes（尤其 netdisk）还要真实账号验证。
4. **反代必须启用 HTTPS**：域名上的 \`/api/baidu/callback\` 和 \`/api/panlocal/\` 应单独路由到 **127.0.0.1:19731**，不要影响其他已有路径。回调 GET 带有一次性 code，必须避免在 Nginx access log、APM、代理日志和 referrer 中留存 URL 查询字符串。
5. **严禁把 SecretKey 写进 APK、仓库、配置截图、聊天消息、日志或命令行历史。**
6. 后端目前 **令牌仅保存在内存**；服务重启后需重新授权。实验期不保存 refresh_token，故不能持久自动续期。公开的 \`/start\` 接口有基本限流；正式公开使用前还应增加防滥用防护、监控与严格反代限制。

## 服务端配置示例（仅供用户授权部署时执行；切勿填写真实值提交到 Git）

在服务器私有环境中设置：

- \`BAIDU_APP_KEY\` = 控制台 App Key
- \`BAIDU_APP_SECRET\` = 轮换后的 SecretKey
- \`PANLOCAL_PUBLIC_ORIGIN\` = \`https://899yx.online\`
- \`PANLOCAL_PORT\` = 自选空闲本地端口（示例为 \`19731\`）

运行 \`node oauth-bridge/server.js\`。生产应通过独立的受限服务账号和进程管理器运行，并让已有 HTTPS 反向代理**精确匹配**以上两个路径。**这里不提供可能覆盖现有 Nginx 配置的自动部署脚本。**

测试（不使用真实密钥、不联网百度）：
\`node --test oauth-bridge/server.test.js\`

## 接口协议

\`\`\`
GET  /api/panlocal/health
POST /api/panlocal/start
GET  /api/baidu/callback?state=...&code=...
POST /api/panlocal/status     {session_id,session_token}
POST /api/panlocal/account    {session_id,session_token}
POST /api/panlocal/search     {session_id,session_token,key}
POST /api/panlocal/filemetas  {session_id,session_token,fs_id}
POST /api/panlocal/logout     {session_id,session_token}
\`\`\`

不需要用户提供百度账号密码、Cookie、access_token、SecretKey。所有 OAuth access_token 保留在你的服务器内存，APP 仅有不可猜测、短时有效的会话识别令牌，接口经 HTTPS 发送。文件名、路径和临时 dlink 在用户手机 APP 内可见，但不会进入默认 TXT 日志。

## 必须验证

- P0：HTTPS 回调和桥接服务健康检查。
- P1：官方授权页面回调、state 校验、令牌交换（不记录码/令牌）。
- P2：本人账号的 uinfo，区分账号授权与授权 scope 不足。
- P3：文件搜索能否找到原始视频（核对全路径、大小、fs_id）。
- P4：filemetas 是否返回有效 dlink，需继续验证 HTTP 200/206 和真实下载能力。
- P5：大文件 Range、下载速度及完整性，未经实测一律不可标注通过。

本轮实现以上入口及单元测试，**不是已经部署成功，也不是已解决下载问题**。若 Android 提示“服务器或网络不可达”，首先检查 OAuth Bridge 是否完成服务器部署，而不是反复修改百度分享下载逻辑。
