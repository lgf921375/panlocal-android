# PanLocal V1.8 手机验收结果与下一步技术证据（2026-10-18）

状态：**源自手机上传的自动诊断 TXT。当前没有构建或宣布新 APK。**

## 本次已验证请求

手机导出 `PanLocal7-auto-20261018-113647.txt`，时间 11:35–11:36：

- OAuth code→token：HTTP 200；uinfo errno=0；refresh token 已保存。
- 搜索：HTTP 200，errno=0，count=1。
- filemetas：HTTP 200，errno=0，dlinkPresent=true。
- 授权下载：HTTP 302→206，Content-Type video/mp4，fileByte=true。
- 浏览器对照（最关键）：
  - 标准 Chrome User-Agent，不带额外 OAuth/Cookie，普通 GET：HTTP 403，读不到字节。
  - 标准 Chrome User-Agent，不带额外 OAuth/Cookie，Range GET：HTTP 403。
  - **User-Agent: pan.baidu.com，不带额外 OAuth，Range GET：HTTP 206，已收到字节。**
  - 标准 Chrome User-Agent，附带 OAuth，Range GET：HTTP 403。
  - 检测标志 `oauthParamStripped=false`，意味着本轮最终 URL 没有需要额外去除的显式 access_token 查询参数。
  - `browserCandidate=false`，不能向用户提供“普通浏览器免登录直链”。

## 有效结论与限制

这一组对照提示在这份有时效的 CDN 地址上，所用 User-Agent 对结果有决定性影响。**不等于已经获得无鉴权、永久可公开分享的链接**。只设置服务端允许的 User-Agent 是调用条件，不应被宣传为破解限速。

用户继续要求：
1. 不改变原 APP 的界面、布局和已通过的本人网盘下载/授权流程。
2. 调试期重装后不再手工输入开发者 SecretKey。仓库公开，不能把用户提供的真实密钥明文写入提交记录；正确的工程路线为构建时从 GitHub Actions 私密 Secret 注入单人调试 APK，随后在手机启动时使用 Android Keystore 初始化本机保存的数据。APK 本身仍可反编译提取密钥，测试结束应轮换。
3. 继续研究在不使用自建服务器的条件下，第三方**原生 Chrome**能否直接使用实际返回的 URL。

## 下一步建议

- 在 GitHub 仓库 Actions secrets 里一次性配置编译用的 SecretKey。需要 repository owner 自行设置；工具端当前未能配置，**不可声称已经注入密钥，也不可声称新包免输入**。
- 代码改造时只改配置初始化与测试逻辑，不重做首页，不扩大服务器端架构。
- 把浏览器无 Cookie/OAuth/专用 UA 的真实 GET 成功作为第一验收条件，未成功之前不向用户提供“免登录公开直链”。
- 任何新的 APK 必须 GitHub Actions 成功编译、核对大小和 SHA-256 后才提供。完整 2.27GiB 文件仍需真机验收。

## 隐私

记录里不能包含 SecretKey、Access Token、完整签名 URL、Cookie。不要把用户在聊天提供的 SecretKey 复制进公开仓库或诊断日志。
