# PanLocal V1.7：无需自己的服务器的浏览器候选直链验证

目标不是重复调用百度 OAuth 的公开下载 API，而是分辨 **“带自己账号授权可以下载”** 和 **“普通浏览器不携带账号信息能否直接下载”**，避免误将 V1.6 的授权直链当成匿名公开直链。

## 已有基础

用户 V1.4 已手机实测：`filemetas` 返回 dlink，带 `access_token`、`User-Agent: pan.baidu.com` 和 `Range: bytes=0-0` 请求，经 302 → HTTP 206，读到 MP4 首字节。
V1.5 屏幕显示下载器实际收到部分数据。V1.6 已有复制经授权请求最终跳转 URL 的功能，但 **未经独立浏览器请求测试**。

## 本轮代码

- 原有保存的 App Key/SecretKey、OAuth Token/自动续期不变，不需要重新配置。
- 选择本人网盘视频后点「获取最终下载直链（自动验证）」；首先使用合法授权请求取得百度最终 CDN URL。
- 以最终 CDN URL 为测试样本，只去掉显式 `access_token` 查询参数，不修改其它 CDN 签名；不向该测试请求额外发送 `Authorization`、`Cookie` 或特殊的 `User-Agent: pan.baidu.com`。
- 使用普通 Android Chrome 风格 User-Agent 分别测试：
  1. `Range: bytes=0-0`：检查是否可在不附加账号凭据情况下分段读首字节。
  2. **不带 Range 的普通 GET**：检查是否能在不附加账号凭据情况下读首字节。
- 每个请求最多读取 **1 字节**，不得擅自下载整个 2.27 GiB 文件。限制 HTTPS 百度 PCS 域名、端口、重定向次数，拒绝不受信任目标。
- 如普通 GET 返回文件数据，且终点 URL 不含已知 OAuth / Cookie / 账号敏感查询字段，则显示「复制普通浏览器候选直链」与「用手机浏览器实际测试该地址」。
- 如普通 GET 返回 403/其它错误，仅显示状态和已脱敏诊断：**不把失败地址伪称为“匿名直链”**。
- 日志只记录 HTTP 状态、是否读到字节、是否去掉 OAuth、是否可供复制，不记录 URL、Token、Cookie、SecretKey。
- 添加纯 Java **离线安全回归测试**，保证 URL 去除 OAuth 参数不破坏其余签名和域名白名单；仍需手机网络实测。

## 重要验收边界

- 同手机 APP 模拟成功 **不代表另外一台设备或 IP 也能访问**；最终必须让没有登录百度的另一台手机在普通 Chrome 中实测。
- URL 可能依然含限时 CDN 签名或授权能力；能不填账号打开，也不是永久有效或无需下载权限检查。
- 没有使用自建服务器、代理、云中转，没有改变百度限速。
- 如请求返回 403，说明当时该候选 URL 未达到要求，不能保证仅通过修改 UA 或去掉令牌可成功。
- 【已有手机实测】V1.4 OAuth 授权下载首字节成功；【待手机实测】V1.7 的无账号 GET 与第三方 Chrome 下载；【不作为本轮成果】分享链接无转存或破解限速。

代码位置：`app/src/main/java/com/panlocal/tool/BrowserDirectLinkProbe.java`、`LocalDriveActivity.java`，测试 `app/src/testPlainJava/com/panlocal/tool/BrowserDirectLinkProbeSelfTest.java`。
