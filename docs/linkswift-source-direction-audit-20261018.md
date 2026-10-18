# PanLocal 技术方向审查：与 LinkSwift 的源码级对照（2026-10-18）

状态：**只读审查 + 技术路线校正；未修改 PanLocal 功能代码，未产生新 APK；没有进行他人分享链接的真机验收。**

## 目标必须纠正

用户最初要复刻的重点是 LinkSwift 式「提供分享链接和提取码 → 从网盘分享页面取得文件列表/会话 → 获取可用于第三方下载器的地址」，并希望 Android APP 直接运行，不要 Termux/网站/远程服务器。原先项目由 V0.8 逐渐改为「百度开放平台 App Key / OAuth → 本人网盘 filemetas → 下载」，虽然已取得 2270.66 MiB 原始视频、206 首字节且下载器下载了少量数据，但**不能视为完成 LinkSwift 分享页复刻**。

## 审查对象

- LinkSwift 上游官方仓库： https://github.com/hmjz100/LinkSwift
- LinkSwift **dev** 分支 `（改）网盘直链下载助手.user.js`，脚本头标称 **1.1.4**，采用 **AGPL-3.0-or-later**（直接复制/改造时需要遵守许可证）。
- 项目自有独立仓库： https://github.com/lgf921375/panlocal-android ，截至核查 PanLocal V1.6。
- 视频搜索截图仅反映第三方宣传标题，**不是其底层代码、破解限速或无限制分享解析的证据**。

## 从上游脚本实际读到的关键代码

1. LinkSwift 自身 README 明言：下载地址基于各服务商的 **公开 API**；**未实现或承诺破解限速机制**。它不是暗藏一个神秘的反限速接口。
2. 它是 `@run-at document-start` 的 Tampermonkey/Greasemonkey 网页脚本，使用 `unsafeWindow`、页面 `localStorage`、Cookie、网页 DOM、`GM_xmlhttpRequest` 等能力，**不是安卓 APK 的独立网络请求程序**。
3. `$baidu.getShareData()` 直接从百度分享网页内的 `unsafeWindow.locals.dump()`、`unsafeWindow.jsToken`、`currentSekey` 等取得 `shareid`、`share_uk`、`bdstoken`、`jsToken`、`sekey`、分享短链接/提取码。
4. `$baidu.getShareFilesUrl()` 使用 `/share/tplconfig` 取得 `sign/timestamp`，再 `POST /api/sharedownload`，带 `primaryid`、`uk`、`fid_list`、`extra.sekey`、`bdstoken`、`jsToken` 等；`errno=0` 仍需区分 `list` 是数组还是字符串。
5. 如果 `res.list` 是 **string**，上游代码显示「此文件大小超过可获取限制，需要保存到网盘后于网盘中下载」，**不会伪造 dlink**。用户 2.27GiB 分享在 PanLocal 早期实机测试出现 `errno=0,list=string`，与这一分支高度吻合；并非简单漏解析的下载地址。
6. 同一脚本也有「个人网盘 filemetas + access_token」分支，`GET /rest/2.0/xpan/multimedia?method=filemetas&dlink=1`，所以 PanLocal 当前使用的公开接口**与 LinkSwift 有重叠**。真正偏差是**把这个分支当成了最终产品目标，缺失网页脚本模式的上下文与用户工作流**。
7. 上游具备 IDM / Aria2 / cURL、设置正确请求头 `User-Agent: pan.baidu.com` 的下载器集成。粘贴原始 URL 到普通浏览器可能报 403；**正确请求头本身不意味着解除网盘限速**。

## 下一轮正确实施方向（优先于继续研发本人网盘下载器）

**P0 源码比对与许可证：** 固定上游具体 commit，对照 share getShareData/getShareFilesUrl / 获取签名 / 下载器集成的实际控制流；识别所有网页浏览器特定依赖。若复制原 AGPL 源码进入本项目，按 AGPL-3.0-or-later 履行许可与开源要求；不照搬脚本之外未经证实的“限速破解”宣传。

**P1 浏览器上下文适配验证：** 安卓 APP 不等于 Tampermonkey，优先做可以打开百度真实网页并读取用户授权会话的 **WebView + 明确受控注入/兼容适配层** 或独立浏览器脚本测试。必须验证 Android 手机拿到的分享页面是否存在实际所需 `locals.dump()`、`jsToken` 等，不凭空认为桌面网页 DOM 可用。不得跨站获取其他账户 Cookie 或要求用户把凭据发给聊天。

**P2 分享链接优先：** UI 首页优先「分享链接 + 提取码 → 文件列表 → 尝试 dlink → 可用性与请求头检测/导出」。个人文件 OAuth 下载作为可选的次级功能或当分享 API 因体积限制拒绝时的清晰说明；不要把要求“必须先拥有文件”写成通用分享解析。

**P3 覆盖分支而不是盲目成功：** 最少测试一件**小于分享接口体积上限的、已授权分享文件**和当前约 2.27GiB 分享文件，分别记录接口响应形状/errno及实机行为，区分“成功返回可下载 URL”“分享端体积限制”“需保存到本人网盘”“鉴权失效”。真实大文件不可成功时明确阻塞，不宣称已复刻。

**P4 客户端输出：** 将可用 URL 与必要下载请求头分别呈现，支持复制 cURL/Aria2 命令和本机下载。实际用户仅需一个“获取地址”入口，隐藏复杂的实验按钮和后台细节。直接使用最终 CDN 地址仍可能有时效、鉴权绑定和速度限制，需真机验证。

## 接手约束

- 不继续把「本人网盘 OAuth + filemetas」当作唯一核心目标。
- 不重复已通过的 V1.4 首字节 HTTP 206 测试和 V1.5 少量本机下载证明。
- 不擅自部署或重启新加坡服务器；不操作 98K、《皓石传奇三》或其它项目。
- 未经真实测试的功能不得标记为通过；编译成功≠下载成功；营销视频标题≠技术实测。
- 只在 PanLocal 独立仓库内研发，不把 SecretKey、Token、Cookie、用户完整签名直链写入 GitHub 或诊断日志。

审查来源：
- https://github.com/hmjz100/LinkSwift/blob/dev/README.md
- https://github.com/hmjz100/LinkSwift/blob/dev/%EF%BC%88%E6%94%B9%EF%BC%89%E7%BD%91%E7%9B%98%E7%9B%B4%E9%93%BE%E4%B8%8B%E8%BD%BD%E5%8A%A9%E6%89%8B.user.js
