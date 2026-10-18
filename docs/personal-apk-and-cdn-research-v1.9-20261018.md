# PanLocal V1.9 — 单用户内置密钥 APK 与 CDN 研究交接（2026-10-18）

## 已执行且核实

- 用户明确要求个人测试 APK 在重装后不再手动输入其百度应用 SecretKey，且不要重做或修改现有界面。
- 公开源码只提交了 `OwnerTestingSeed.java` 的 32 字节占位符，不上传真实密钥到 GitHub 仓库/Actions/日志。
- APP 启动时，只在本机没有保存开发者密钥的情况下初始化个人编译版的密钥，并继续交给已有 `LocalCredentialVault` 使用 Android Keystore 保护。用户手动删除后，当前安装不会自动再填回。
- GitHub Actions 编译占位版成功，运行 `39096588292`，源码 commit `07e6dace1622e2b1790ed808d40c06ce0144bc66`。
- 本地生成**含用户提供 SecretKey 的私人调试 APK**，只修改 DEX 一处 32 字节占位，随后重算 DEX SHA1/Adler32，重新封装并生成 APK Signature Scheme v2（RSA-3072/SHA256）签名。以独立解析器验证签名结构、SHA256 内容摘要、证书签名、公钥、ZIP 条目、未压缩资源对齐和 DEX 完整性，全部通过。
- 私人 APK SHA-256：`3ed228918e4b0f0aefb493faaed1db9091e3edf1183aa7d660d8b30a167b1ebb`，大小 77,668 字节。**仅 ChatGPT 会话内交付，不能上传公开 GitHub 或分享给其他人。**
- APK 用私人新证书签名，手机上升级自其他 debug 签名可能需要卸载；是否能安装并实际自动初始化仍必须实机确认。
- 保留项目界面、本人网盘 OAuth、搜索和下载功能。V1.9 在成功读取 CDN 数据后仅增加最终授权 URL 与独立 UA-only 测试的**主机名级** `CDNTRACE` 诊断（绝不记录完整查询串、Token、Cookie 或签名 URL）。

## 真实测试结果与研究结论

来自用户上传的 V1.8 `PanLocal7-auto-20261018-113647.txt`：
- OAuth 认证、搜索、dlink 获取均成功。
- 授权请求 302 → 206，实际读到 `video/mp4` 首字节。
- 普通 Chrome GET/Range：HTTP 403；
- 百度专用 User-Agent，不带额外 OAuth：HTTP 206，首字节成功；
- 普通 Chrome User-Agent，带 OAuth：HTTP 403。

这只能证明对**当时有效的这条百度 CDN URL**，UA 条件有决定性影响，不能推导出适合原生 Chrome 任意匿名下载的永久公开 URL。客户端能观测到的是 CDN 网关 URL，并不必然包括背后的源站文件实际存储位置。LinkSwift README 明确称下载链接基于各服务商公开 API，未实现或承诺破解限速：
https://github.com/hmjz100/LinkSwift

## 未来验证（不要重复已成功的步骤）

1. 真机装入 V1.9 私人 APK，确认跳过 SecretKey 填写；首次 OAuth 授权仍可能需要。
2. 执行「获取最终下载直链」，导出自动诊断 TXT，比较 `CDNTRACE` 最终主机及 `BROWSERTEST` 结果。
3. 在原生 Chrome 无插件/无登录时仍是 403 则明确标记 **匿名分享地址受服务端条件限制**，不得伪造成功直链或声称存在可随意访问的内部存储 URL。
4. 如果用户更重视第三方直接下载，技术上只能继续评估 CDN 原本是否提供满足这些条件的可分享 URL；若没有，不使用自有服务器又不重新托管文件，就不能保证原生 Chrome 匿名任意下载。
5. 2.27 GiB 完整下载、跨设备测试尚未验证；不要将首字节成功等同于整文件成功。
