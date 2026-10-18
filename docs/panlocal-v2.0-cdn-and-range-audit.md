# PanLocal V2.0：CDN 访问条件定位与 2.27GiB 断点续传完整性

## 起点（真实手机证据，非推断）

用户 V1.8 手机日志已证实本人授权请求 HTTP 302→206，文件首字节可读；在同一签名 CDN 地址上：
- 不加额外 OAuth、使用 `User-Agent: pan.baidu.com` 的 Range 请求返回 HTTP 206。
- Chrome UA，不加 OAuth 的普通 GET/Range 返回 403。
- Chrome UA 加 OAuth 的 Range 返回 403。
所以**当前 CDN 地址不具备原生 Chrome 的匿名可复制公开直链特性**。不要用“已取得 CDN URL”替代这项验收，更不能宣称已获得未公开的源站存储地址。

## 本轮源码修改

1. **界面不改设计**，本人网盘、搜索、下载、复制候选地址、自动诊断、OAuth 加密保存以及个人 APK 测试密钥初始化都保留；版本号为 2.0.0。
2. `BrowserDirectLinkProbe` 增加唯一新增的关键对照：`User-Agent: pan.baidu.com` + **普通 GET（不发送 Range）**、不额外附加 OAuth。它可用于判断“百度 UA 有效”是否同时依赖分段 Range。与之前已通过的 UA-only Range、Chrome GET、Chrome Range、Token-only 对照并行记录，所有请求最多读取首字节、禁止把 OAuth/完整 URL 写进日志。
3. `LocalDriveActivity` 增加每个 HTTPS 百度 PCS CDN 跳转的**主机名级** `CDNTRACE`（跳数、HTTP 状态、是否 30x、Content-Range 与 Accept-Ranges 是否存在、响应长度），不记录 URL 路径、签名、查询参数、Cookie、token。已存在的最终 CDN 主机名单独保留。
4. **大文件安全修复**：`DownloadRangeValidator` 严格解析 HTTP 206 的 `Content-Range` 起止字节与实际文件总长，与个人网盘 API 返回的预期长度比较；如果不匹配、超长、服务器返回 200 却要求从非零偏移续传、响应 Content-Length 不合理，就不允许写入下载文件。每次流读取也不允许超过本次响应声明的结束位置。下载完成前刷新输出流。
5. 增加**纯 Java 离线回归测试**，覆盖总文件长度超过 2GiB 的 Range、偏移错误、总大小变化、响应大小不符、416、缺失 Range、HTTP200 非零续传等。CI 必须同时通过这些测试和 Android 编译才可宣称源码构建成功。
6. GitHub 源码/工件仍**只有 SecretKey 占位值**。个人包应在本地补入用户先前提供的密钥、重签名，不上传公开仓库或云 Actions；优先使用之前保留的个人签名私钥以便覆盖安装已有私人版。即使是私人 APK，密钥也可被反编译，禁止公开传播。

## 验收与后续判断

- 【已通过（历史手机实测）】本人文件首字节下载和 V1.8 四组基础访问条件。
- 【需 CI】V2.0 离线 Range 安全测试、编译、APK 结构与签名核对。
- 【需手机】V2.0 `CDNTRACE`、新增 UA-only 无 Range、下载 2.27GiB 整文件、实际暂停/继续、哈希/播放及更新覆盖是否成功。
- 【仍未通过】匿名普通 Chrome、另一台未登录手机通过临时 URL 直接下载。
- 【技术边界】最终 CDN 主机域名 ≠ 百度内部对象存储源站；用户未提供服务端源站地址，任何对私有存储 IP/URL 的猜测都不能作为真实证据。不能凭调整 URL 参数保证绕过百度的服务端权限/限速。
