# PanLocal V1.0：无需自己的服务器的 OAuth 授权实验

## 为什么修改授权方式？

用户在两种安卓浏览器里访问 V0.9 的 `response_type=token&redirect_uri=oob` 都只显示“百度 OAuth2.0”空白页面。当前百度官方文档主要描述 `response_type=code`，且针对旧版 Implicit Grant 有 Referer 与绑定域名检查。现阶段不再默认旧版隐式授权可用。

官方指南：https://openauth.baidu.com/doc/doc.html
错误表：https://openauth.baidu.com/doc/appendix.html

## V1.0 新增可选的本地授权码流程

1. 在本机“本地授权 · 我的网盘（实验 V1.0）”输入自己开发者应用的 **App Key**，不是 SecretKey。
2. 点击“打开百度官方授权页（获取 code）”。PanLocal 通过手机浏览器打开 `https://openapi.baidu.com/oauth/2.0/authorize?response_type=code&redirect_uri=oob`。
3. 若授权成功，百度官方网页显示一次性 **code**，用户返回手机 APP 粘贴到密码式输入框。
4. 用户本人在手机 APP 的另一个密码框**临时输入自己最新、已轮换的 SecretKey**，点击“本机兑换令牌并验证账号”。
5. APP 在 HTTPS 下直接调用百度官方 `oauth/2.0/token` 接口；不向自有域名或 ChatGPT 发送凭证、不写 GitHub、不写 APK 常量、不存进 TXT 日志或偏好设置。将输入框清空；结果仅保存于 Activity 内存，关闭即失效。
6. 调用本人网盘 `uinfo` 先验证令牌；成功之后可搜索文件，测试 `filemetas&dlink=1`。

## 安全限制

该流程由最终用户同时掌握 App Key、SecretKey 与百度账号，只适合**本人私用、自控设备的实验**。第三方开发者不能在公开分发的 APK 中接收或保存生产 SecretKey。虽然源码没有明文密钥，**手机恶意软件、剪贴板、输入法、系统调试及 APP 的本地运行环境仍可能暴露密钥；软件不能保证完全隔离**。如果此前密钥通过截图泄露，应先在百度开发者控制台轮换，再考虑测试。完成后可以主动清空令牌、退出 APP。不得将 code、SecretKey、Access Token 或下载地址发到聊天。

出于与官方接口的兼容，当前令牌兑换按百度文档使用 GET，因此 SecretKey 会包含在发送给百度官方 HTTPS 服务的查询参数中。这与“固定编译到 APK”不同，但仍有被设备侧网络代理及百度服务端访问日志记录的风险。建议仅在用户明确理解风险、已轮换密钥、设备可信时测试；面向多人或长期使用应改用服务端安全保管 SecretKey 的 OAuth Bridge。

## 注意

- V0.9 隐式授权兼容入口保留但不推荐；原分享解析、TXT 诊断及 V0.8 独立服务端模式保留。
- 百度是否允许该 App Key 使用 `code + oob` 尚未实测；可能出现 unsupported_response_type、invalid_scope 等错误。
- 目前只是源码与 APK 编译层面的验证。账号授权、权限、文件搜索、2.27GB 视频 dlink、下载能力均待真实手机测试。
- 每轮测试只分享已脱敏的错误码或 TXT 诊断；不要求上传密钥。
