# PanLocal V1.9：个人测试 APK 本地嵌入密钥 + CDN 终点证据

## 用户目标
- 需要重装 Android APK 后无需再输入自有的 SecretKey。
- **不改变** V1.8 已有个人网盘首页、搜索、下载、调试/日志布局。
- 不经过自有新加坡服务器，继续分析真实 CDN 响应和原生 Chrome 浏览器能否免账号访问。
- 先前手机实测：带 OAuth + 百度 UA HTTP206；**仅百度 UA、无 OAuth** 亦 HTTP206；普通 Chrome UA 无 OAuth HTTP403；Chrome UA 加 OAuth HTTP403。证明 UA 对当前地址有明显影响，不等于可公开分享无限期链接。

## 构建流程
- 公开 GitHub 仓库**不得包含真实 SecretKey**。源码 `OwnerTestingSeed.java` 仅使用长度相同的**测试占位字符串**。GitHub Actions 编译生成 **无真实密钥的标准调试 APK**。
- 个性化 APK 仅在独立本地构建环境**替换占位字符串**，更新 DEX 校验和，并以全新调试签名签署 APK。不能把个性化 APK 上传公开仓库或其 Actions 工件。
- APK 自动初始化密钥至 Android Keystore 加密保存。手动保存的密钥优先；手动删除后不重新自动恢复（除非卸载重新安装）。
- 个人 APK 中的密钥可通过反编译恢复，**不是加密保护**。仅限用户自己安装；一旦流出，应轮换开发者 SecretKey；用户明确授权这一便捷性风险。
- 复制的下载 URL、OAuth Token、Cookie 和 SecretKey 均不进入公开日志。

## CDN 诊断
- 增加 `CDNTRACE`：仅记录成功授权读取的最终 HTTPS 百度 PCS 主机名、查询串是否存在、最终 URL 是否显式携带 OAuth；记录百度 UA 独立测试实际访问的终点主机名。
- 这一证据有助于区分「前置 dlink」与「HTTP 302 后 CDN 地址」。它**不可能保证暴露百度内部真实存储桶/IP/源站**：客户端通常只有受控 CDN 入口。
- 用户目标「匿名普通浏览器任意分享」依然受限：HTTP403 对照已证实当前签名地址不满足原生 Chrome 直接下载；不得以改变 URL 参数声称破解服务端权限或限速。

## 验收
- 【待 Android 实机】APK 打开后是否自动保存密钥（不需要手输）；仍需首次百度官方 OAuth 授权来获得可用登录 Token。
- 【待 Android 实机】CDNTRACE 主机域名与预期一致；普通 Chrome UA 请求返回状态。
- 【需要用户手机实际执行】整段 2.27GiB 文件下载/校验、另手机直接下载仍未确认。
