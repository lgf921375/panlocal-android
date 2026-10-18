# PanLocal V2.2：真正导致个人 APK 无法启动的根因与验收

## 用户问题
V2.0 个人版 APK 无法打开。此前只核对 ZIP、DEX 校验和、APK 签名，未进行 Android 模拟器启动实测。

## 取证与技术修复
- Android 35 模拟器：未修改 APK 安装并冷启动成功。已修改的个人 APK 最初可安装但启动进程退出；仅重新签名、不修改 DEX 的 APK 冷启动成功。
- 解析 V1.9、V2.0 个人 APK 的 `classes2.dex` string_id 表发现：占位 `QQQQ...` 所在索引前后是 `Probe` 与 `RANGE`；直接替换成实际 SecretKey 导致新增一个不符合升序排列的 string_id，DEX 结构因此不被 Android 正常接受。即使 SHA-1、Adler32 和 APK v2 签名都有效，DEX 内部语义结构仍错误。
- **V2.2 修复：** `OwnerTestingSeed` 采用 32 字节、固定以 `Q` 开头的可逆表示；通过 base62 数字编码成 30 位 base85 字符并附加边界字符，确保替换后 string_id 仍位于 `Probe` 与 `RANGE` 之间。启动时解码并交已有 Android Keystore 保存。注意这种表示不是加密，公开 APK 依然可以被反编译提取调试密钥。
- 公共 GitHub 只保留占位符。真实密钥仅用于本地生成个人 APK，没有提交仓库、CI 日志或公开工件。
- 使用 Google Android SDK 官方 `apksigner` v2/v3 完成个人 APK 签名。因旧个人版使用不同的签名密钥，更新可能需要卸载旧版，应先导出需要保留的诊断 TXT。
- `scripts/personalize_apk.py` 在替换前验证 DEX 占位出现次数、固定长度和 string_id 邻居的排序不变；避免重犯旧问题。

## 真实通过的模拟器测试

Android 35，GitHub Actions run [39103337090](https://github.com/lgf921375/panlocal-android/actions/runs/39103337090)，已完成 **success**。执行实际 adb install、am start、等待进程存活、检查 FATAL EXCEPTION：

1. 原始 CI APK：**通过**。
2. 仅重新签名、DEX 不变 APK：**通过**。
3. 使用**虚构测试密钥**的完整替换、zipalign、官方签名 APK：**通过**。

本地用户实际密钥的 V2.2 个人 APK另经过密钥无明文、可逆解码往返、DEX 字符串排序、ZIP 完整性、APK v2/v3 签名核验，SHA256 `baa078f5f924d881aa266a019e78a0c5a159193520bd6b33f7f8a19726fe3f0d`。此文件仅在用户当前聊天中交付，不上传公开 GitHub。

## 与技术突破目标的边界

已知实际手机证据：本人授权或只用百度指定 UA 的请求能返回 HTTP 206；普通 Chrome UA 的独立 GET/Range HTTP403。**这不是“匿名 Chrome 公开下载直链已破解”的证据**。现有 HTTP 重定向只能证明对外 CDN 访问地址，不能推断真实内部存储源站 IP 或地址。若服务端拒绝普通浏览器，单靠查询源站或更改 URL 不能保证越过其访问控制。

用户仍需实际手机测试 V2.2 启动、自动初始化密钥、OAuth 恢复、搜索与后续完整下载。不要再把纯编译或纯签名核验标为实机通过。
