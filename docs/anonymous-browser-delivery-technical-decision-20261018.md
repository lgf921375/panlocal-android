# PanLocal：B 目标（第三方原生 Chrome 直接匿名下载）技术决策记录

## 决策日期
2026-10-18。研究阶段；**不得生成、发布新 APK 来重复 UA / OAuth 测试**。不更改现有安装包，不部署用户自己的服务器。

## 用户硬性要求
- 单人 Android APP 可以使用已保存的百度合法授权辅助取得链接；
- 生成一个无需插件、无需特殊 User-Agent、无需百度登录，可发给其他人的普通 Chrome HTTPS 文件下载链接；
- 不经过自己的服务器；
- 用户不想再反复安装「修改请求头/多一项测试」的 APK。

## V2.2 确定事实（14:44 手机诊断记录）
- 个人密钥初始化=true；OAuth、本人网盘搜索、filemetas 成功。
- 原始地址 `d.pcs.baidu.com` 经 302 跳到 `yqd0.baidupcs.com`，返回 MP4 首字节。
- 百度指定 User-Agent `pan.baidu.com`，无额外 OAuth，GET=200、Range=206；
- 标准 Chrome User-Agent：GET=403、Range=403，即使附加 OAuth 依旧 403；
- 因此本文件 CDN URL 仍要求 User-Agent 条件；**地址栏粘贴 URL 不能控制 Chrome 发出的 User-Agent**。
- 未取得百度内部存储源站 IP/URL；CDN 终点仅说明外部交付入口。

## 已对照其他公开源码（非同路径重复尝试）
- OpenList `drivers/baidu_netdisk/util.go`：
  - 官方 `linkOfficial`：调用 /rest/2.0/xpan/multimedia?method=filemetas，再带 OAuth 和 `User-Agent: pan.baidu.com` 取 Location；
  - 另有 `linkCrack`：网页版 /api/filemetas?dlink=1&web=5&origin=dlna；
  - 另有 `linkCrackVideo`：视频 /api/mediainfo?type=VideoURL&nom3u8=1&dlink=1；
  - 但这两项仍在返回值上附加自定义 UA header。源码**没有证明**可供无插件 Chrome 匿名下载超过 2GiB 的文件。
  - 原始来源：https://github.com/OpenListTeam/OpenList/blob/main/drivers/baidu_netdisk/util.go
- LinkSwift 官方 README 明确声明：通过网盘公开 API 取得地址，不承诺、不实现解除限速；也需要下载器按需带请求头。
  - 原始来源：https://github.com/hmjz100/LinkSwift
- Alist / OpenList 文档关于百度大文件（约 20MB 以上）需要 `User-Agent: pan.baidu.com`：
  - https://openlistteam.github.io/docs/zh/guide/drivers/baidu.html

## 真实可落地的 B 技术架构（无需自己的服务器，但需第三方存储）
**PanLocal 在安卓本地下载本人文件 → 从手机以 HTTPS 分片上传到第三方公开对象存储 → 返回该服务的公开 HTTPS URL**。
这样普通 Chrome 直连的是第三方存储，而不是仍受 UA 控制的百度 CDN。不是对百度服务端鉴权的绕过。

可供用户选择的具体托管平台之一：Cloudflare R2。
- 官方文档：https://developers.cloudflare.com/r2/objects/upload-objects/
- 文件 2270.66MiB，可通过 S3 兼容分片上传；官方规定 multipart 每部分 5MiB–5GiB，最多 5TiB，能恢复分片。
- 文档：https://developers.cloudflare.com/r2/buckets/public-buckets/，启用公开 bucket 可用 r2.dev 测试 URL（官方标注开发用途、有请求速率限制），生产分发应使用自定义域名。
- 价格：https://developers.cloudflare.com/r2/pricing/，标准类每月有 10 GB-month 免费存储、100万 Class A 与1000万 Class B 免费操作量，公网传出带宽不计费，可能需要绑定支付工具开通账户，超限将计费。
- 用户拥有文件分发权时，自己启用公开访问后，访问者直接从 R2 取得文件。PanLocal 不保存用户云存储私钥到公开源码，凭据只保存在 Android Keystore。
- 用户手机上传过程需要实际向手机下行约2.27GiB（从百度）+向云上行约2.27GiB；受本地网络上行影响。
- R2 仅可在用户**明确接受第三方另存一份文件**并开通账户后使用。它不是在百度原有存储位置提供一个新的无限制原始 URL。

## 严格验收门槛
1. 在不改变现有 UI 核心设计的前提下，先对**用户授权的第三方存储**生成一个公开 URL，再使用未登录、不安装插件的 Chrome 真正接收文件数据。
2. 对 2270.66 MiB 文件，必须完整上传并检查字节数/散列，接收端核查大小和完整性。
3. 在此之前，不再发布「更多 UA profile / 链接参数组合」的 APK 作为技术突破。
4. 若用户坚持**必须由百度原始 CDN 分发**、并且不接受其他存储/中转，则目前的证据说明 B 无可交付方法，不能凭空承诺绕过 UA 检查。
