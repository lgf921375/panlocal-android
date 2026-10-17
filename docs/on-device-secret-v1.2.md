# PanLocal V1.2 — 本机加密记住开发者密钥

## 目标

用户只自己使用 PanLocal，希望不必每次在手机重新输入百度开放平台 SecretKey 和 App Key；服务器远程工具额度暂时用尽。

**不把任何真实 SecretKey 写死到 APK 或公开 GitHub 仓库。** 即使私有使用，写死的密钥仍可通过反编译 APK 泄露。采取一次输入、手机端安全存储的替代实现。

## 新增实现

- 增加 `LocalCredentialVault.java`，使用 Android Keystore 生成不可导出的 AES-256-GCM 密钥。APP 私有 SharedPreferences 只存 App Key、随机 IV 和已加密 SecretKey。
- `LocalDriveActivity` 会自动填充已保存的 App Key。用户第一次输入 App Key 和已经轮换的 SecretKey，点击“在此手机加密保存 App Key + SecretKey”；后续兑换 OAuth code 时，SecretKey 框留空则自动从本机解密。
- 提供“删除本机保存的开发者密钥”；主动删除本机密文和 Android Keystore 密钥条目。
- 授权码 `code` 和访问令牌 `access_token` 仍不写入持久存储，临时令牌仅保留在当前 Activity 内存；每次有效授权过期后还需要新的 code。此版本**只消除开发者密钥的重复输入**，不承诺永久免登录。
- 现有 APK Manifest 为 `android:allowBackup=false`，安卓系统不应自动备份应用私有数据。卸载或清除应用数据后，保存的密钥丢失，需要重新输入。
- TXT 诊断只记录保存/清除成功与异常类型，不会记录真实凭据、密文、IV、code、token 或带签名 dlink。

## 安全边界与验收

- **先轮换旧版截图暴露过的 SecretKey 与 SignKey**；不要把新密钥发到聊天或 GitHub。
- 本机加密只能降低本地静态读取风险；系统被 root、手机遭恶意软件入侵或 APP 运行时受注入，仍可能暴露密钥。没有绝对安全。
- APP 在 `code` 交换时仍按百度官方授权接口使用 GET 传输 `client_secret`；只对 HTTPS 官方百度域发起请求；可能受代理或服务端访问日志影响。
- GitHub Actions 的 debug APK 可能采用不同临时签名证书，导致跨版本不能覆盖安装；卸载重装会清除所有本机私有密钥及未导出的 TXT 日志。更新前先导出日志和确认可重新配置密钥。
- V1.2 编译是否成功以 GitHub Actions 为准，保存/重启/删除/本人账号授权需要安卓实机验收。大文件 dlink 实际下载仍待验证。

### 手机验收步骤

1. 安装 V1.2 → “本地授权 · 密钥加密保存 V1.2”。
2. 填入 App Key 和最新 SecretKey → “在此手机加密保存 App Key + SecretKey”，确认保存提示。
3. 退出该页面重新进入，确认 App Key 自动填充、状态显示已保存 SecretKey（密码框不回显）。
4. 按正常流程打开官方授权网页获取一次性 code → code 框填写，SecretKey 框**留空** → 点击兑换并验证账号。
5. 验证成功后自行搜索文件及首字节下载测试；如需删除设备存储数据，点“删除本机保存的开发者密钥”。

不需要服务器、Remote Desktop Commander 或 SSH。
