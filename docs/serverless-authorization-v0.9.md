# PanLocal V0.9：无服务器个人网盘模式（实验）
   
## 目的与状态

当用户无法使用新加坡服务器或远程工具时，尝试旧版百度网盘 OAuth 的 `response_type=token`（隐式授权）方式。**只做可行性实验**：不代表百度 2026 年仍允许该方式，也不代表大文件 dlink 可成功下载。

百度当前网页版 OAuth 文档仍要求授权码 `code` 用 `client_secret` 才能换 access_token；Android SDK 的回调也仅提供 code。因此如隐式授权不支持，本软件不能在完全不使用服务器又不泄露 SecretKey 的前提下实现标准授权。

## 使用说明（不需网站、Termux、远程桌面、SSH）

1. 安装 V0.9 APK，进入主页 **“不依赖服务器 · 我的网盘（实验）”**。
2. 从百度开发者控制台取得 **App Key**（不是 SecretKey），仅填写在手机 APP 内。
3. 点 **“打开百度官方免服务器授权页”**，手机浏览器会打开 `https://openapi.baidu.com/oauth/2.0/authorize`。
4. 如果官方页面显示 `unsupported_response_type` 或 `redirect_uri_mismatch`，说明本授权方式被拒绝。可返回 APP 告知错误类型，不要提供 token 或 SecretKey。
5. 如果官方页面返回 Access Token 或官方百度域名的含 `access_token` 的链接，由用户手动复制，返回 APP 点击“粘贴”并“验证授权”。**不要在聊天中上传令牌。**
6. 验证成功后，在 APP 搜索原始文件，确认路径和大小，再请求个人文件 `dlink`。`dlink` 是否真能下载还需要后续验证。

## 边界

- 无服务器模式仅使用 `openapi.baidu.com` 官方授权域和 `pan.baidu.com` 个人文件 API。
- 不将 SecretKey、Cookie、Access Token 写进公开 GitHub、APK 常量或 TXT 日志。Token 只存在当前 Activity 内存，不自动持久化；离开页面后需重新授权。
- 为避免 APP 从官方授权页读取账号密码，这一版使用手机外部浏览器，并要求用户亲自在 APP 里粘贴返回结果。系统剪贴板和浏览器历史仍可能接触敏感令牌，应避免分享或录屏。
- 返回 `code` 而非 `access_token` 时，不能将 SecretKey 嵌入 APK 继续兑换。
- V0.8 服务器桥接模式仍作为可选入口保留，旧版分享解析、TXT 诊断不变。
- 大文件直链、Range、真实下载、免限速均未实测，未经真实验证不得标为通过。

参考：
- https://openauth.baidu.com/doc/doc.html
- https://openauth.baidu.com/doc/android.html
- https://developer.baidu.com/question/detail.html?id=177
