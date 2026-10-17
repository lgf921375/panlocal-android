# PanLocal V1.1 — 百度 dlink 下载权限首字节诊断

## 根因证据

用户个人网盘模式已通过 code 授权、uinfo 验证、原文件搜索（2270.66 MiB）以及 filemetas 返回 dlink。用户把 dlink 粘贴到普通浏览器后，百度返回 error_code=31326, user is not authorized, hitcode:119。

百度开发者中心有完全相同的错误记录，官方相关回答明确提醒检查下载接口的必需 User-Agent：https://cloud.baidu.com/ask/148 。百度官方 Go SDK Download 下载方法明确设置 `User-Agent: pan.baidu.com`，源码：https://github.com/baidu-netdisk/baidu-drive-sdk-go/blob/main/baidudriver/api/download_file.go 。OpenList 文档也要求该 UA 来处理较大的网盘文件。

**不能据此宣称完整 2.27 GiB 视频已可下载，也不能声称 31326 完全由 UA 导致；需实机证实。**

## V1.1 新增

在“本地授权·下载权限检测”页面：

1. 直接取得 dlink 时新增“应用内验证直链首字节（推荐）”按钮。
2. 可将**先前的、仍有效的百度 dlink**贴进本机临时输入框，再点“测试下载地址的首字节”，无需再次 OAuth。
3. 使用 GET，设置 `User-Agent: pan.baidu.com` 和 `Range: bytes=0-0`，读取至多一个文件数据字节，不主动下载整部视频。
4. 若百度返回 JSON 错误，仅抽取 HTTP 状态、error_code、hitcode；诊断 TXT 不保存 dlink 原文、完整跳转 URL、SecretKey、code 或 token。
5. 手动处理最多 5 次重定向，只允许 HTTPS 的百度 PCS 下载域名，不把认证令牌放入 HTTP 请求头或非百度域名。
6. 保留原分享列表、TXT 导出、本地 OAuth 和服务器桥接模式，未部署任何服务器。

## 手机验收

- 首字节检测返回 206：Range 受支持且读到文件内容，是大文件下载链路的关键进展，但仍待全文件校验。
- 返回 200 且读到字节：可能未遵守 Range，只能说明当前能读到数据，不能证明断点续传。
- 返回 31326、hitcode119：要记录是否已按约定带 UA；继续排查账号权限、链接过期、其他下载头要求。
- 如果出现跳转到未允许的 CDN 域名，会明确拒绝跳转；需要静态评估官方域名后才能加白名单，不得任意放开 HTTPS 域名。
- 当前仅通过源码提交与 CI 编译后才标记编译通过；手机真实下载测试待用户复现。

## 安全提醒

用户曾在聊天里提供含有效签名参数的完整 dlink，属于敏感临时链接。过期前不应再公开分享，出现安全疑虑时应撤销/更新有关授权。开发者后台曾出现 SecretKey 明文截图，需先轮换。后续只上传删除 URL 等敏感内容的 TXT 日志。
