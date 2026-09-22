# Bilibili API

[![CI](https://github.com/abcLiyew/BiliBili-API/actions/workflows/ci.yml/badge.svg)](https://github.com/abcLiyew/BiliBili-API/actions/workflows/ci.yml)

> [!WARNING]
> **本项目仅供学习与测试使用，请勿滥用。**
> 本项目为开源项目，不接受任何形式的催单与索取，也不容许存在付费内容。
> 利用本项目提供的接口、文档等造成的不良影响及后果，与作者无关。
> 由于本项目的特殊性，可能随时停止开发或删档。

## 项目简介

Bilibili API 是一个用于获取哔哩哔哩（Bilibili）平台数据的Java库。该项目提供了一系列API接口，可以获取用户信息、视频、动态内容、直播信息等数据，也支持搜索、用户空间、短链解析，以及登录（扫码 / 密码 / 短信）与 WBI 签名。

## 接口来源与覆盖范围

### 文档来源

本库的端点、参数与返回字段，参照开源文档项目
**[SocialSisterYi/bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect)**
（B 站 API 文档合集；⚠️ 上游已于 2026-01-30 停止维护并关停，原文档站已下线，
其内容仅存于各 fork 中）；
本项目取用的是它留下的 fork **[realysy/bili-apis](https://github.com/realysy/bili-apis)**。
WBI 签名算法（`wts` / `w_rid` / 密钥重排表 `MIXIN_KEY_ENC_TAB`）出自该项目的逆向研究，
见 issue [#631](https://github.com/SocialSisterYi/bilibili-API-collect/issues/631) 与
[#885](https://github.com/SocialSisterYi/bilibili-API-collect/issues/885)，在此致谢。

> 本库与上述文档项目**没有隶属关系** —— 它只是本库的参考资料之一，也不保证与本库同步。

> ⚠️ **协议归属**：上游文档项目采用 **CC-BY-NC 4.0**（禁止商业使用）。本库**不是**该项目的衍生作品，
> 仅参考其中记录的事实性接口信息 —— **该协议不适用于本库**，本库自身仍为 [MIT](#许可证)。

⚠️ **文档只是参考，本库以真机实测为准。** 两者不一致时一律以实测为准，以下为已核实的分歧
（均已写进代码注释）：

| 接口 | 文档标注 | 本库实测 |
|---|---|---|
| `x/v2/reply`（评论列表） | Wbi | **匿名、不带签名即返回 `code=0`**（2026-09-22 实测 `replies=3`） |
| `x/player/online/total`（在线观看数） | APP 端、需签名 | **匿名、不带签名即返回 `code=0`**；且 `total` / `count` 是**字符串数字**（`"690"`） |
| `x/web-interface/wbi/search/all/v2`、`…/wbi/search/type` | 需 WBI 签名 | **匿名、不带签名即返回 `code=0`**（库内仍走签名链路，只是多一个 `nav` 依赖） |
| `x/space/upstat` | Cookie | 匿名返回 `code=0` 但 `data` 是**空对象**，**需要凭据**才有数据（2026-09-22 复验仍然如此） |
| `x/player/wbi/playurl` | WBI + Cookie | 匿名、未签名即返回 `code=0`（默认 720P）；**带 `/wbi/` 的那条路径**会 HTTP 412（含签名 / 凭据 / 连换 6 代指纹都试过）⇒ 库内改走**不带 `/wbi/`** 的 `x/player/playurl`，同分钟同凭据立刻 `code=0` |
| `api.vc.bilibili.com/**`（动态旧域） | 有完整文档 | **整站已下线**，本库对应常量已标 `@Deprecated` |
| `x/polymer/web-space/seasons/list` | 取 `season_id` 的入口 | **已 HTTP 404 下线**；本库改从 `arc/search` 的 `vlist[].season_id` 取 |
| WBI 文档中的 `w_rid` 示例值 | 共 5 个 | 独立复算后**只有 2 个可复现**（正文 walkthrough 与 PHP demo），其余 3 个对不上 |

另外，B 站接口随时可能变更（本库就遇到过整站下线、字段增删、验证码换代），
**升级本库前建议先用你自己的场景跑一遍**。

⚠️ **还有一种"不是文档分歧、而是文档根本没提"的坑：`Referer` 会决定成败。**
最典型的是 `x/web-interface/ranking/v2`（排行榜）—— 同一端点、同一分钟、只改 `Referer`：
**站根 `https://www.bilibili.com/` 换来 `-352 风控校验失败`，而排行榜页
`https://www.bilibili.com/v/popular/rank/all` 或干脆不带 `Referer` 都是 `code=0`**（本库已实测 4 次复现）。
而"站根"恰好是全库其它端点的默认值 ⇒ **本库对该端点单独使用排行榜页 `Referer`**。
（同一分钟用 `x/web-interface/popular` 对照过：热门**不**敏感。所以"B 站要 Referer"这种话不能一概而论。）

### 已覆盖的接口

下表按 `BilibiliEndpoint` 中实际使用的端点整理，**「匿名」= 不注入凭据即可用**。
凡标「凭据」的，本库只提供注入点，**不内置任何账号**。

| 门面 | 请求的端点 | 门槛 |
|---|---|---|
| `CardInfo` | `x/web-interface/card?mid=` | 匿名 |
| `CardInfo` / `Live` | `room/v1/Room/get_info?room_id=` | 匿名 |
| `BilibiliClient` | `x/web-interface/view?bvid=` / `?aid=` | 匿名 |
| `Dynamic` | `x/polymer/web-dynamic/v1/detail?id=` | 匿名 |
| `Dynamic` | `x/polymer/web-dynamic/v1/opus/detail?id=` | 匿名 |
| `Dynamic` | `x/polymer/web-dynamic/v1/feed/space?host_mid=` | 🔒 凭据 |
| `Dynamic` | `x/polymer/web-dynamic/v1/feed/all`（关注流） | 🔒 凭据 |
| `ShortChain` | 短链跳转解析 + 上述视频 / 直播 / 动态端点 | 匿名 |
| `Login` | `passport.bilibili.com/x/passport-login/web/qrcode/generate`、`…/qrcode/poll` | 匿名（凭据随响应头 `Set-Cookie` 下发） |
| `Login` | `…/captcha?source=main_web` | 匿名（极验 v3 前置参数） |
| `Login` | `…/web/key`、`…/web/login` | 匿名（密码登录） |
| `Login` | `…/web/sms/send`、`…/web/login/sms` | 匿名（短信登录） |
| `Login` | `x/web-interface/nav`、`…/web/cookie/info` | 🔒 凭据（状态校验） |
| `Search` | `x/web-interface/wbi/search/all/v2`、`…/wbi/search/type` | 匿名（实测免签名） |
| `UserSpace` | `x/space/wbi/acc/info` | 🔏 签名 + 🔒 凭据 |
| `UserSpace` | `x/space/wbi/arc/search` | 🔏 签名 + 🔒 凭据 |
| `UserSpace` | `x/polymer/web-space/seasons_archives_list` | 匿名（须先有真实 `season_id`） |
| `UserSpace` | `x/space/upstat`（累计播放 / 阅读 / 获赞） | 🔒 凭据 |
| `UserSpace` | `x/relation/followers`、`x/relation/followings`（粉丝 / 关注**列表**） | 🔒 凭据 |
| `UserSpace` | `x/relation/stat`（关注数 / 粉丝数，**只给数量**） | 匿名（⚠️ 与上一行正相反：数量匿名可读，名单必须登录） |
| `VideoExtra` | `x/web-interface/view/conclusion/get`（AI 摘要） | 🔏 签名 + 🔒 凭据 |
| `VideoExtra` | `x/player/playurl`（视频流地址，MP4 / DASH） | **匿名**（凭据只提升清晰度，见下） |
| `VideoExtra` | `x/web-interface/view/detail`（**一站式详情**：`View` 内含 `stat` 13 项 + `Tags` + `Related`） | 匿名（标签 / 相关推荐 / 状态数**都由它顺带给出**，本库不为它们单独发请求） |
| `VideoExtra` | `x/player/online/total`（在线观看数） | 匿名（文档标 APP 端需签名，实测免签） |
| `Comment` | `x/v2/reply`（评论列表；⚠️ `oid` 要的是 **aid**） | 匿名（文档标 Wbi，实测免签；门面收 `bvid` 时内部先经 `view` 换算出 `aid`） |
| `LiveExtra` | `room/v1/Room/playUrl`（直播拉流地址） | 匿名（`cid` 是**直播间号**，与视频的 `cid` 同名不同物） |
| `LiveExtra` | `live_user/v1/Master/info`（主播信息） | 匿名（入参 `uid` 是**主播 uid**，不是房间号） |
| `Ranking` | `x/web-interface/ranking/v2`（排行榜） | 匿名，🔴 **要带排行榜页 `Referer`**（站根会…**间歇**换来 `-352`） |
| `Ranking` | `x/web-interface/popular`（热门视频） | 匿名（站根 `Referer` 即可 —— 与排行榜正好相反） |
| `Content` | `x/web-interface/history/cursor`（观看历史） | 🔒 凭据 |
| `Content` | `x/v2/history/toview`（稍后再看） | 🔒 凭据 |
| `Content` | `x/v3/fav/folder/created/list-all`（收藏夹目录） | 🔒 凭据 |
| `Content` | `x/v3/fav/folder/info`（收藏夹详情） | 取决于夹本身：公开夹匿名可读，含 `attr=1` 的夹匿名 **`-403`**（⚠️ **不能拿 `attr` 反推公开性**） |
| `Content` | `x/v3/fav/resource/list`（收藏夹内容，`pn` 翻页） | 同上（空列表要看 `info.media_count` 有没有内容 —— 见下） |
| `Content` | `x/article/viewinfo`（专栏**信息**，**不含正文**） | **匿名**（本门面唯一免凭据的一项；旧路径 `x/article/view` 匿名 `-352` ⇒ **正文不做**） |
| `Danmaku` | `x/v1/dm/list.so`（某个分 P 的**全部弹幕**，返回 **XML**） | 匿名（⚠️ 入参是 **`cid`**，不是 `aid` / `bvid`；`Referer` 实测**无影响**） |
| `Search` | `x/web-interface/search/square`（热搜榜） | 匿名（**不走签名出口**；榜单在 `data.trending` 里） |
| `Comment` | `x/v2/reply/reply`（楼中楼，**只有一层**） | 匿名（`root` 是**一级评论的 `rpid`**，不是 aid） |
| `Comment` | `x/emote/user/panel/web`（表情包面板） | 🔒 **凭据**（匿名 `code=0` 但 `packages` 为空；⚠️ **需要 Cookie，不需要签名**） |
| `LiveExtra` | `room/v1/Area/getList`（直播分区树，一级 + 二级） | 匿名（⚠️ `parent_area_id` 实测**无效**，本库**不暴露**该参数） |
| `Wbi` | `x/web-interface/nav`（只取 `data.wbi_img`） | 匿名（`img_key` / `sub_key` 是公共值，不是凭据） |

> 表中「凭据」指登录 Cookie（至少含 `SESSDATA`），注入方式见下文
> **「动态列表返回 -352 / 412 怎么办」**；「签名」指 WBI 签名，走 `UserSpace` /
> `VideoExtra` 时库内已自动完成，未覆盖的接口可用 `Wbi` 门面自己签。
>
> ⚠️ **两种"看起来像空，其实是不同的事"**（本库一律**抛异常**，不乱返回空结果）：
> ① `emote/user/panel/web` 在**缺凭据**时返回 `code=0` 但 `packages` 为空 —— 那是"没给我数据"，
> 不是"这个账号没有表情包"；
> ② `fav/resource/list` 的空 `medias` 分两种：`info.media_count > 0` 却一条不给 ⇒ **抛**（多半是 `pn` 越界），
> `media_count = 0` 的空夹 ⇒ **正常返回空列表**。⇒ 判据是**先看码、再看长度**。

## 功能特性

- **用户信息获取**：获取用户名称、头像、等级、签名、粉丝数等基本信息
- **视频信息获取**：获取用户视频投稿数量、视频详情等
- **动态内容获取**：获取用户动态列表、动态详情、动态图片等；动态长图由 Java2D 自绘，无需浏览器
- **直播信息获取**：获取用户直播间状态、直播间信息等
- **短链解析**：把 `b23.tv` 短链还原成视频 / 直播间 / 动态，并直接给出对应数据
- **搜索**：综合搜索与分类型搜索（视频 / 用户），自动剥离结果里的 `<em>` 高亮标签
- **用户空间**：UP 主账号信息、投稿列表、合集稿件、累计播放/阅读/获赞（`getUpStat`）、
  粉丝与关注列表（`getFollowers` / `getFollowings`）
- **AI 视频摘要**：按 `bvid` / `cid` 取 B 站的 AI 总结
- **视频流地址**：`getPlayUrl` 给出一条可播放的地址；**MP4 通道**封顶 720P，
  **DASH 通道**可达 1080P（⚠️ DASH 的音视频是两条独立流，本库**不合流**）
- **我的内容**：观看历史（游标翻页）、稍后再看（一次给完）、收藏夹目录
  —— 这三项都在 `Content` 门面，且**全是 GET 只读**
- **视频一站式详情**：`getViewDetail` 一次拿到 `View`（内含 `stat` 13 项）/ `Tags` / `Related` / `Card`
  —— **标签、相关推荐、状态数都不必再单独发请求**
- **评论列表**：`Comment.getReplies`，支持**按热度 / 按点赞 / 按时间**三种排序（收 `bvid` 时自动换算 `aid`）
- **直播拉流**：`LiveExtra.getLiveStream` 给 CDN 播放地址、`getMasterInfo` 给主播信息 —— **都不需要凭据**
- **榜单**：`Ranking.getRanking`（分区排行榜）/ `getPopular`（热门视频）—— 同为匿名可读
- **弹幕**：`Danmaku.getDanmaku(cid)` 取某个分 P 的**全部弹幕**（含 `maxlimit`，可判断有没有被截断）。
  ⚠️ 入参是 **分 P 的 `cid`**（不是 aid / bvid —— 拿它的正路是 `getViewDetail` 的 `pages[].cid`）；
  ⚠️ **没有翻页**，`maxlimit` 就是硬上限；零弹幕与"没人评论"一样是**合法结果**
- **热搜榜**：`Search.getHotSearch(limit)` —— 零门槛，条数在 `trending.list`，`trackid` 是超出 `long` 的超长字符串
- **楼中楼**：`Comment.getSubReplies(aid, root, pn, ps)` —— ⚠️ **只有一层**（别写递归），
  `root` 是**一级评论的 `rpid`**（从 `getReplies` 的 `replies[i].rpid` 拿）
- **表情包**：`Comment.getEmotePanel()` —— ⚠️ 本库**唯一需要凭据**的一项"评论域"能力
  （要 Cookie、**不要签名**；响应很大，实测 68 个包 / 约 1555 个表情）
- **收藏夹详情与内容**：`Content.getFolderInfo(mediaId)` / `getResources(mediaId, pn, ps)`
- **专栏信息**：`Content.getArticleInfo(cvId)` —— ⚠️ 这是**本门面唯一免凭据**的方法（其余四项都要 Cookie）。
  🔴 **计数别读错**：全局统计在 **`stats.*`**（`stats.like` 才是"这篇文章有多少赞"），
  顶层的 `like` / `coin` / `favorite` / `attention` 是**"我"的交互状态**（匿名恒 0）；
  `isAuthor` 只能当"**已登录**"的指示器（**不能当"是我的"** —— 拿别人的专栏读也是 `true`）；
  `inList` 更弱：**它连"已登录"都指示不了**，只反映"请求有没有带会话指纹"，详见下方用法段
  —— ⚠️ 门槛**取决于夹本身**（公开夹匿名可读，含 `attr=1` 的夹匿名 `-403`），且 `-403` 是两义码
- **直播分区**：`LiveExtra.getAreaList()` 一次拿回一级 + 二级分区树 —— ⚠️ **刻意没有入参**：
  文档里的 `parent_area_id` 实测**完全不起作用**（只在返回结果上自己筛）
- **登录**：扫码 / 密码 / 短信三条链路，以及 `getCredentialStatus()` 凭据状态校验
  —— 长驻进程可用它把"凭据失效"从静默失败变成一个可判的布尔值
- **WBI 签名**：`Wbi` 门面可给**任意** B 站 WBI 接口算签名（`wts` + `w_rid`），
  用于本库尚未覆盖的接口 —— **不需要凭据**，密钥由本库按天缓存

## 环境要求

- JDK 17+
- Maven 3.6+

> 无需安装浏览器：动态长截图已改用 **Java2D 自绘**（自 v0.9.13.5 起），不再依赖 Chrome / Selenium。
> 字体为 jar 内置（Noto Sans SC 子集），因此无图形界面的 Linux 环境也能正常出图。

## 安装方法

### Maven
**由于目前项目尚未发布到中央仓库，需要有以下两种方式导入本地仓库：**
- 1 克隆仓库到本地，然后执行以下命令：
```bash
mvn install
```
- 2 在[Release](https://github.com/abcLiyew/BiliBili-API/releases/tag/0.9.30-beta)中下载最新版本的jar包，并将其复制到本地Maven仓库中。
在你的Maven项目中，将以上代码添加到`pom.xml`文件的`<dependencies>`标签内，即可引入本库。
```xml
<dependency>
    <groupId>com.esdllm</groupId>
    <artifactId>bilibili-api</artifactId>
    <version>0.9.30-beta</version>
</dependency>
```

## 快速开始

### 获取用户信息

```java
// 初始化CardInfo对象
CardInfo cardInfo = new CardInfo();
long uid = 3546774476163227L;

// 获取用户名
String userName = cardInfo.getUserName(uid);
System.out.println("用户名: " + userName);

// 获取用户头像URL
String face = cardInfo.getFace(uid);
System.out.println("头像URL: " + face);

// 获取用户等级
Integer level = cardInfo.getLevel(uid);
System.out.println("用户等级: " + level);

// 获取用户签名
String sign = cardInfo.getSign(uid);
System.out.println("用户签名: " + sign);

// 获取粉丝数
Integer follower = cardInfo.getFollower(uid);
System.out.println("粉丝数: " + follower);

// 获取获赞数
Integer likeNum = cardInfo.getLikeNum(uid);
System.out.println("获赞数: " + likeNum);

// 获取视频投稿数
Integer archiveCount = cardInfo.getArchiveCount(uid);
System.out.println("视频投稿数: " + archiveCount);

// 获取完整用户卡片信息
Card card = cardInfo.getCard(uid);
System.out.println("完整用户信息: " + card);
```
### 获取用户动态信息
<div style="color:red;"><strong>获取动态列表与图片耗时较长，建议在多线程中调用。</strong></div>
> 注：动态截图功能**不需要** Chrome 浏览器 —— 现已改为 Java2D 自绘（自 v0.9.13.5 起），
> 请勿再按旧说明安装 Chrome；旧版本（≤ v0.9.13.3）才依赖 Selenium + Chrome。

```java
// 初始化Dynamic对象
Dynamic dynamic = new Dynamic();

// 获取动态详情
String dynamicId = "1055444954124386328";
BilibiliDynamicResp.Data.Card card = dynamic.getDynamicDetail(dynamicId);
System.out.println("动态详情: " + card);

// 获取动态图片
String opusId = "1020454757493375033";
BufferedImage dynamicImg = dynamic.getDynamicImg(opusId);
ImageIO.write(dynamicImg, "png", new File("dynamic.png"));

// 获取用户动态列表
String uid = "497078180";
List<Dynamic.DynamicInfo> dynamicInfoList = dynamic.getDynamicInfoList(uid);
for (Dynamic.DynamicInfo info : dynamicInfoList) {
    System.out.println("动态ID: " + info.getDynamicId());
    System.out.println("标题: " + info.getTitle());
    System.out.println("描述: " + info.getDesc());
    System.out.println("发布时间: " + info.getTime());
    System.out.println("标签: " + info.getTag());
    System.out.println("BV号: " + info.getBvid());
    System.out.println("图片URL: " + info.getImageUrl());
    System.out.println("转发动态ID: " + info.getShareDynamicId());
    System.out.println("----------------------------");
}
```
### 获取用户直播信息
```java
// 初始化CardInfo对象
CardInfo cardInfo = new CardInfo();
long uid = 3546774476163227L;

// 获取直播信息
BilibiliCardResp resp = cardInfo.getBilibiliLiveResp(uid);
System.out.println("直播信息: " + resp);
```

### 登录（扫码）

```java
Login login = new Login();

// ① 申请二维码（有效期 180 秒）。qr.getUrl() 就是二维码内容，自行渲染成图给用户扫
QrCodeLogin qr = login.getLoginQrCode();

// ② 等用户扫完并在手机上确认（阻塞；二维码失效或超时会抛 IOException）
LoginCredential credential = login.waitForLogin(qr.getQrcode_key(), 180_000L);

// ③ 注入凭据 —— 之后所有出站请求自动带上登录态
HttpPolicy.setCookie(credential.getCookieHeader());
```

```java
// ④ 之后随时校验这枚凭据是否还有效（未登录以返回值表达，不抛异常）
CredentialStatus status = login.getCredentialStatus();
if (!status.isLoggedIn()) {
    // 凭据已失效 —— 该重新登录，而不是继续发注定失败的请求
}
```

- **扫码**链路开箱即用；**密码登录**（`getRsaKey` + `loginByPassword`）与**短信登录**
  （`sendSmsCode` + `loginBySms`）都要先过**极验 v3** 验证码，而极验必须由调用方在浏览器里
  用官方 JS 过验 —— 本库**不含任何浏览器 / 打码逻辑**，只负责把参数交给你、把你的过验结果送出去。
- **凭据由调用方保管**：本库不内置账号、不落盘，日志与 `toString()` 里的值一律打码。
- 注入凭据后，动态列表（`feed/space`）与关注流（`feed/all`）才可用，详见下文
  **「动态列表返回 -352 / 412 怎么办」**。
- 相关类型位置：`Login` 在 `com.esdllm.bilibiliApi.bilibiliApi`，
  `QrCodeLogin` / `LoginCredential` / `CredentialStatus` 在 `com.esdllm.bilibiliApi.model.data.pojo.login`。

### 给本库未覆盖的 WBI 接口签名

B 站有一批接口要求带 `wts` + `w_rid` 签名，缺失或算错一律返回 `-403 访问权限不足`
——与"真的没有权限访问"从响应上**区分不出来**。签名器已对外暴露，你可以用它调**本库尚未覆盖**的接口：

```java
Wbi wbi = new Wbi();

Map<String, String> params = new LinkedHashMap<>();
params.put("mid", "946974");

// ① 直接给出可以发出去的完整 URL（参数值已按 WBI 口径编好码）
String url = wbi.signedUrl("https://api.bilibili.com/x/space/wbi/acc/info", params);

// ② 或者只拿签名串，自己拼
String query = wbi.signQuery(params);   // mid=946974&wts=1758xxxxxx&w_rid=<32 位 md5>
```

三点务必注意：

- **不需要凭据**：`img_key` / `sub_key` 由 `nav` 匿名下发，是公共值；本库缓存当天那一份，
  不会每次签名都多打一次请求。
- **不要自己再编码一遍**：返回值里的参数值已经按 WBI 口径编好（空格是 `%20` 而**不是** `+`）。
  这也是本门面只给"编好的 query / URL"、**不给"参数表"**的原因——用 `URLEncoder` 重编会让签名对不上。
- **签名覆盖全部参数**：所以 `baseUrl` 里不能自带 query（带了就漏签），请把所有参数都放进 `params`。

本门面**只算签名、不发请求**；自己发请求时，本库的限流 / 重试 / 指纹策略不覆盖它。
若手上已有密钥（或 `nav` 一时取不到），用四参重载离线签名，一次出站都不发：

```java
String query = wbi.signQuery(params, imgKey, subKey, 1700384803L);
```

### 视频流地址（唯一一项匿名可用）

```java
VideoExtra videoExtra = new VideoExtra();

// MP4 通道：一条能直接播的整文件，实测封顶 720P
PlayUrl mp4 = videoExtra.getPlayUrl("BV1tgPie2E3w", cid);
String url = mp4.getDurl().get(0).getUrl();

// DASH 通道：能到 1080P，但音视频是【两条独立流】，本库不合流
PlayUrl dash = videoExtra.getPlayUrl("BV1tgPie2E3w", cid, 80, 16);
String videoStream = dash.getDash().getVideo().get(0).getBaseUrl();
String audioStream = dash.getDash().getAudio().get(0).getBaseUrl();
```

- **匿名即可用**，凭据买到的是**更高清晰度**，不是"能不能用"。
- `qn` 是**期望**不是承诺：MP4 通道传 `80` 也只回 `quality=64`（实际值以 `mp4.getQuality()` 为准）。
- 地址带时限（实测约 2 小时），**不要持久化缓存**。
- 这条链路**可能因为出口信誉整条失败（HTTP 412）**，且历史上反复过 —— 拿到 `412` 不是参数写错。

### 我的内容：观看历史 / 稍后再看 / 收藏夹目录

三者都在 `Content` 门面，**都需要注入凭据**；未注入时会抛 `IOException`（`-101`），
或返回 `code=0` 但**空列表**（收藏夹目录那种最隐蔽）。

```java
Content content = new Content();

// 观看历史：翻页是【游标式】的 —— 把上一条的 cursor 原样传进去就是下一页
HistoryCursor first = content.getWatchHistory(20);
HistoryCursor.CursorPos c = first.getCursor();
HistoryCursor next = content.getWatchHistory(20, c.getMax(), c.getView_at(), c.getBusiness());

// 稍后再看：不分页，一次给完
ToViewList toView = content.getToView();

// 收藏夹目录：list[].id 才是查夹内内容要用的 media_id（fid 是另一套短 id）
FavFolderList folders = content.getFavoriteFolders(497078180L);
```

### 弹幕（唯一返回 XML 的接口）

入口是 `Danmaku` 门面。🔴 **入参是分 P 的 `cid`，既不是 `aid` 也不是 `bvid`** ——
传错不会报"参数错"，只会得到 HTTP 400 或一份没有弹幕的空 XML。
拿 `cid` 的正路是 `VideoExtra#getViewDetail(bvid)` 的 `pages[].cid`。

```java
Danmaku danmaku = new Danmaku();

// 建议用这个重载：maxlimit 是判断【有没有被截断】的唯一依据
DanmakuXml xml = danmaku.getDanmaku(cid);
for (DanmakuItem d : xml.getDanmaku()) {
    System.out.printf("[%.2fs] %s%n", d.getTime(), d.getText());
}
boolean truncated = xml.getDanmaku().size() >= xml.getMaxlimit();   // 达到了上限 = 被截断

// 只要文本列表：
List<DanmakuItem> list = danmaku.getDanmakuList(cid);
```

- **匿名可用**，不需要凭据、不需要签名。
- 🔴 **没有翻页**：`maxlimit` 就是硬上限（实测见过 `300` / `1000` 两种，随视频设置变化），
  达到上限时本库会打一条 `WARN` —— 那是"被截断了"，不是"这个视频只有这么多弹幕"。
- **零弹幕是合法结果**（返回空列表，不抛异常），与"没人评论"同理。
- `p` 属性实测是 **9 段**（老文档只写 7 段），解析是**容错**的：段数不足只会让个别字段为 `null`。
- 发弹幕 / 删弹幕是**写操作**（需 `csrf` 且会改动账号），本库不做；实时弹幕流（WebSocket）也不在范围内。

### 收藏夹详情与内容 / 热搜 / 楼中楼 / 表情包 / 直播分区（B2）

```java
Content content = new Content();
FavFolderInfo info = content.getFolderInfo(3526698880L);          // 夹详情
FavResourceList page = content.getResources(3526698880L, 1, 20);  // 夹内视频（pn 翻页）

Search search = new Search();
HotSearch hot = search.getHotSearch(10);                          // 热搜榜：榜单在 trending.list 里

Comment comment = new Comment();
SubReplyPage subs = comment.getSubReplies(aid, rootRpid, 1, 20);  // 楼中楼：root 是一级评论的 rpid
EmotePanel emotes = comment.getEmotePanel();                      // 🔒 需要凭据（不要签名）

LiveExtra liveExtra = new LiveExtra();
List<LiveArea> areas = liveExtra.getAreaList();                    // 一级 + 二级分区树，无入参
```

- ⚠️ **收藏夹的门槛取决于夹本身**：公开夹匿名可读，含 `attr=1` 的夹匿名 `-403`（**不能拿 `attr` 反推**）。
- ⚠️ **楼中楼只有一层** —— 每条 `replies[].replies` 都是 `null`，**不要写递归**。
- ⚠️ **表情包需要凭据**：匿名返回 `code=0` 但 `packages` 为空，本库**抛异常**而不是给空列表
  （否则"我没带凭据"会被读成"这个账号没有表情包"）。
- ⚠️ **`getAreaList()` 刻意没有入参**：文档里的 `parent_area_id` 实测**完全不起作用**，
  要按父分区筛请在返回结果上自己挑。

### 专栏信息（B4）

```java
Content content = new Content();
ArticleInfo info = content.getArticleInfo(4538122L);   // cv4538122，匿名即可读

info.getStats().getLike();   //  35  ← 🔴 这篇专栏的【全局】点赞数
info.getLike();              //   0  ← "我"有没有赞过（匿名恒 0，不是文章的赞数！）
```

- 🔴 **同一个词在三层里含义不同，这是本方法最容易读错的地方**：

  | 层 | 字段 | 含义 | 实测 |
  |---|---|---|---|
  | ① 全局统计 | `stats.*`（8 项） | **这篇文章本身**的计数 | `view=3231` / `favorite=120` / `like=35` / `reply=9` / … |
  | ② "我视角" | `like` / `coin` / `favorite` / `attention` | **"我"**有没有赞 / 币 / 藏 / 关注 | 恒 `0` / `false` |
  | ③ 两个布尔 | `isAuthor` / `inList` | ⚠️ **归因不同，见下** | 见下方 2×2 |

- 🔴 **第 ③ 层的两个布尔不要一起理解** —— 2×2 实测（同一篇文章，凭据是**另一个账号**，
  所以四格都是"读**别人的**文章"）：

  | 请求 | `isAuthor` | `inList` |
  |---|---|---|
  | 零 Cookie | `false` | `false` |
  | 仅匿名指纹 | `false` | **`true`** |
  | 仅凭据（不含指纹） | **`true`** | **`true`** |
  | 凭据 + 指纹 | **`true`** | **`true`** |

  - `isAuthor` 与凭据**完全同向** ⇒ 可以当"**已登录**"的指示器；
    但**不能当"是不是我的"** —— 四格都在读别人的文章，有凭据时照样 `true`。
  - 🔴 `inList` **不跟凭据走，只跟"请求有没有带会话指纹"走**。零 Cookie 是 `false`，
    带上 `buvid3` / `buvid4` 就变 `true` ⇒ **它连"已登录"都指示不了**。
    ⚠️ 本库运行时**必然**携带匿名指纹 ⇒ 真实调用拿到的 `inList` **通常是 `true`**。
    **任何"用 `inList` 判断未收藏 / 未登录"的写法都是错的。**
- ⚠️ **只给"信息"，不给"正文"**：正文走旧路径 `x/article/view`，实测两次都不是 `code=0`
  （先 `-352`、后 `-509`，**两者都是风控码、码值会变**），本库不做。
- ⚠️ 顺带一提：**"注入凭据后没差异"不等于"凭据没生效"** —— 正文里那 11 个统计/交互字段匿名与登录
  取值**完全一致**；会变的只有第 ③ 层那两个布尔，而且**变的原因不同**（`isAuthor` 跟凭据、
  `inList` 凡带任何会话标识就是 `true`）⇒ 想验证"凭据到底生效没"，看 `nav.isLogin` 比看这两个字段更可靠。
- 📌 **方法论**：本项目此前"匿名 A/B"里的"匿名"格其实是**零 Cookie**，而"凭据"格必然带指纹 ——
  **两个变量同时在变**，所以把 `inList` 的差异错归给了凭据。⇒ **"匿名"不是一个状态**，
  只变一个变量的对照才叫对照。

## 数据模型
项目中包含多种数据模型，用于表示不同类型的数据。
- `Card`: 用户卡片信息
- `BilibiliCardResp`: B站用户的名片响应
- `BilibiliDynamicResp`: B站用户的动态响应
- `DynamicInfo`: 动态信息
- `BilibiliLiveResp`: B站用户的直播响应
- `QrCodeLogin` / `LoginCredential` / `CredentialStatus`: 登录（二维码、凭据、凭据状态）
- `AccInfo` / `ArchiveSearchResult` / `SeasonsArchives`: 用户空间（账号信息、投稿列表、合集）
- `UpStat` / `RelationList`: 用户累计数据、粉丝/关注列表
- `SearchAllResult` / `SearchTypeResult` / `SearchVideo` / `SearchUser`: 搜索结果
- `AiSummary`: AI 视频摘要
- `PlayUrl`: 视频流地址（MP4 的 `durl` / DASH 的 `dash` 两条通道）
- `HistoryCursor` / `ToViewList` / `FavFolderList`: 观看历史、稍后再看、收藏夹目录
- `ArticleInfo`: 专栏信息（⚠️ 全局统计在内部类 `ArticleInfo.Stats` 里，不是顶层字段）
  
## 注意事项
1. 请注意，使用本库时，请遵守哔哩哔哩的API使用规则和限制。
2. **本库采用 MIT 许可证（允许商业使用）**；但接入 B 站接口仍需遵守其用户协议，请自行评估合规风险。
3. 使用本项目请遵守B站用户协议和相关法律法规。
4. 请合理控制请求频率，避免对B站服务器造成过大压力。

## 动态列表返回 -352 / 412 怎么办

`Dynamic.getDynamicInfoList(uid)`（桌面端 `x/polymer/web-dynamic/v1/feed/space`）
**匿名已经过不去**。2026-09-13 实测：

| 请求方式 | 结果 |
|---|---|
| 不带任何 Cookie | HTTP 412（风控页，不是 JSON） |
| 带匿名指纹 `buvid3/buvid4` | HTTP 200，业务码 `-352` |
| 再补 `web_location` + `dm_img_*` 客户端指纹参数 | 仍 `-352` |
| 换代理出口 IP | 无效（机房 IP 反而直接 412） |

也就是说**必须注入真实登录 Cookie**。本库不内置任何凭据，只提供注入点：

```java
// 方式一：代码注入
HttpPolicy.setCookie("SESSDATA=xxx; bili_jct=xxx; ...");

// 方式二：启动参数（在静态初始化时读取）
// java -Dbili.cookie="SESSDATA=xxx; bili_jct=xxx; ..." -jar app.jar
```

- Cookie 取自浏览器开发者工具里请求头的 `Cookie` 整串（至少含 `SESSDATA`）。
- 注入后会与匿名指纹 Cookie 合并，**用户 Cookie 的键优先**（同名键不会被指纹值覆盖）。
- 会在日志/`HttpPolicy.describe()` 里只输出键名，值一律打码。
- 其它多数端点匿名可用；**需要凭据的是这几处**：动态列表与关注流（`Dynamic`）、
  用户空间的 `acc/info` / `arc/search` / `upstat` / 粉丝与关注列表（`UserSpace`）、
  AI 视频摘要（`VideoExtra`）、观看历史 / 稍后再看 / 收藏夹目录（`Content`），
  完整清单见上文「已覆盖的接口」。
  （`VideoExtra#getPlayUrl` 与 `Content#getArticleInfo` 是这批里仅有的两个例外 —— **匿名也能用**。）

⚠️ 另有一种**静默空**形态（2026-09-21 实测，比上表更隐蔽）：带上匿名指纹时返回
`code=0` 而 `items` 是**空数组** —— 它与"这个 UP 真的没发过动态"在响应上**完全同形**
（键名一字不差，只有数组长度不同）。注入凭据后同一条请求立刻返回 `items=13`。
所以**看到空列表先别下"没动态"的结论**，先用 `Login#getCredentialStatus()`
确认凭据确实生效了。

> **排障**：注入后仍持续 412 时，先看日志里这一行（首次出站必然打印、之后仅在身份变化时打印）：
> ```
> 出站身份：Cookie 键=[buvid3,buvid4,SESSDATA]，设备指纹来源=登录 Cookie，UA="..."
> ```
> 它证明的是"请求真的带上了什么"，而不是"配置里写了什么"。
> 若这里显示 `未携带任何 Cookie`，问题是拼装/注入，与风控无关；
> 若键名齐全却仍 412，就只剩出口 IP / 该指纹已被标记这一类原因了
> （同一台机器上 `x/frontend/finger/spi` 若仍 200，说明不是整站封 IP，
> 而是该风控只在动态 feed 这条路径上更严 —— 此时换出口 IP 或换指纹才可能有效）。

### Cookie 里带了 buvid 时会跳过匿名指纹领取

`buvid3/buvid4` 由匿名指纹接口（`x/frontend/finger/spi`）领取。但合并规则是"用户 Cookie 优先"，
所以当注入的 Cookie 已经带 `buvid3`/`buvid4` 时，领来的值<b>根本进不了最终 Cookie 头</b> ——
唯一效果是多打一次请求，而这次请求恰好排在业务请求前几百毫秒，正是风控最敏感的连发形态。

因此 `AnonymousSession` 在这种情况下<b>直接跳过领取</b>（出站 Cookie 逐字节不变，只少一次请求）。
副作用有两个，都是有意的：

- "空列表换身份重试"（`DynamicService.getInfoList`）在此情况下是空转（换不了身份），会被跳过；
  **想让它重新生效，就把 Cookie 里的 `buvid3`/`buvid4` 去掉**（只保留 `SESSDATA`），
  这样设备指纹改由匿名指纹提供、可轮换。
- 日志里轮换会显示 `由登录 Cookie 提供（轮换不改变出站身份）`，这**不是**故障。

### ⚠️ Linux 上必须装字体（fontconfig），否则长图渲染会整体失败

长图由 Java2D 自绘，字体取自 jar 内置的 Noto Sans SC 子集，**但这并不等于可以不装 fontconfig**：
Java 在 Linux 上无法绕过平台字体管理器 —— `Font.createFont` 内部同样会走
`FontManagerFactory` → `X11FontManager` → `FontConfiguration` → 读 fontconfig。
在既无 fontconfig、也没有任何字体目录的系统上，第一次画字就会抛：

```
java.lang.InternalError: java.lang.reflect.InvocationTargetException
Caused by: java.lang.RuntimeException: Fontconfig head is null, check your fonts or fonts configuration
```

**修复**（Debian/Ubuntu，二选一即可，约 5MB）：

```bash
apt-get install -y fontconfig fonts-dejavu-core      # 推荐：同时给系统兜底字体
apt-get install -y fontconfig                        # 最小：只装 fontconfig 也行
```

验证：

```bash
fc-list | wc -l          # > 0 即可
```

> 注意这是 **`Error` 而不是 `Exception`**：任何 `catch (Exception)` 都兜不住它。
> 调用方务必用 `catch (Throwable)` 包住渲染步骤，并在失败时降级为纯文字，
> 否则会出现"日志里只有一条 AsyncUncaughtExceptionHandler、用户什么都收不到"的现象。
> 本库已把字体加载失败包装成带修复指引异常（见 `FontRegistry`），但调用方的边界仍建议兜 `Throwable`。

### 动态标题

B 站"带标题的动态 / opus 文章"的标题在 **opus 端点**的 `MODULE_TYPE_TITLE.module_title.text` 里，
长图会把它画在作者行下方（字号更大、伪粗体）。注意：

- `v1/detail` 端点**根本不返回标题字段**（图文动态连 `desc` 都是 null），所以标题只能从 opus 端点取；
- 多数图文动态**本来就没有标题**，此时 `RenderModel.title` 为 `null`，渲染器直接跳过。

## 版本历史
- 0.9.13.1-beta: 初始版本
- 0.9.21-beta: 新增 `HttpPolicy.setCookie` / `-Dbili.cookie`，支持注入真实登录 Cookie
  （`v1/feed/space` 匿名已无法通过）；长图渲染早已改为 Java2D 自绘，不再依赖 Selenium
- 0.9.22-beta: **修复「Cookie 正确却仍 412」** —— 关闭 Unirest 自带的 cookie 管理
  （它会把响应 `Set-Cookie` 回放到后续请求，与显式 `Cookie` 头叠加后被 B 站判风控）。
  触发场景：先请求直播接口、紧接着请求动态 feed，稳定 412
- 0.9.23-beta: 新增 `HttpPolicy.hasCookieKey/cookieProvidesDeviceId/cookieKeys`；
  登录 Cookie 自带 buvid 时**跳过匿名指纹领取**（出站内容不变，少一次请求，
  避免启动时"指纹+feed"连发）；此时"空列表换身份重试"自动跳过；
  出站时打印一行**实际 Cookie 键名 + 指纹来源**，用于区分「请求形状问题」与「出口 IP 被标记」
- 0.9.24-beta: 动态 feed 改用**与真实网页一致的请求头形状**
  （`Accept: application/json, text/plain, */*` + `Referer: https://space.bilibili.com/<uid>/dynamic`），
  替代默认的"文档型 Accept + 站点根 Referer"；`BilibiliHttp.get(url, accept, referer)` 新增头部覆盖重载
  （只开放这两个头、且**替换**而非追加，避免同名头两份）；出站身份日志追加"请求头"标签
- 0.9.25-beta: 新增**关注流**数据源 `Dynamic.getFollowFeed()`（`x/polymer/web-dynamic/v1/feed/all`）。
  起因：真机实测 `feed/space` 被 WAF 以 `{"code":-412,"message":"request was banned"}` **按客户端封禁**
  （同机同 Cookie 下 `spi` 200、`feed/all` 200，只有这条路径被拒；换 buvid / 换头 / 拉长间隔都无效）。
  关注流一轮 1 次请求覆盖所有已关注 UP，是这类环境下的可用替代源。
  `Dynamic.DynamicInfo` 相应新增两个**附加**字段：`uid`（`module_author.mid`，用于归到订阅）
  与 `userName`（`module_author.name`，省掉一次名片请求）；既有字段与签名一字未动
- 0.9.26-beta: **修正 `FontRegistry` 的错误假设**（原注释称"内置字体不查 fontconfig、不依赖系统字体"，
  在 Linux 上不成立）：字体加载改为 `catch (Throwable)`（缺 fontconfig 时抛的是 `InternalError`），
  连系统兜底字体都拿不到时抛带修复指引的 `IllegalStateException`，而不是把 `InternalError` 原样上抛。
  README 补「Linux 上必须装 fontconfig」章节与验证方法
- 0.9.28-beta: **修两个"长图上没有标题"的问题**（2026-09-14 真机复现）：
  ① opus 端点的 `MODULE_TYPE_TITLE` 之前被解析器忽略 —— 表现是"有作者、有正文、有图，就是没标题"。
  `RenderModel` 新增 `title` 字段，渲染器画在作者行下方（21px、伪粗体、可换行、支持 emoji 贴图）；
  `load()` 的"opus 是否算成功"判定放宽为"有正文块**或**有标题"。
  ② 直播推荐（`DYNAMIC_TYPE_LIVE_RCMD`）原来读 `live_rcmd.content.title`，那个路径**永远取不到**——
  标题其实在 `live_rcmd.content.live_play_info.title`（`content` 还是被双重编码的 JSON 字符串），
  结果是渲染出一张只有头像昵称的近空白卡片（实测 756x162）。现在会画「封面 + 直播标题 + 分区/人气」。
  ③ 另外 `load()` 改为"正文/图片/标题全空就报错"，让调用方降级成纯文字，而不是安静地发一张空卡片。
  新增离线单测 `RenderModelLoaderOpusTest` 与直播推荐两个用例
- 0.9.29-beta: **登录能力落地** —— 新增第 6 个门面 `Login`：扫码（`getLoginQrCode` /
  `getLoginStatus` / `waitForLogin`）、密码（`getRsaKey` + `loginByPassword`）、短信
  （`sendSmsCode` + `loginBySms`），以及**凭据状态校验** `getCredentialStatus()`
  —— 未登录以返回值表达、不抛异常，长驻进程据此判断"该重新登录了"。
- 0.9.29-beta: **WBI 签名能力落地，并把签名器对外暴露** —— 新增 `Wbi` 门面
  （`signQuery` / `signedUrl` / `invalidateKeys`，含"自带密钥、零出站"的离线重载）。
  签名算错只表现为 `-403`、与"真的没权限"同形，所以把一整套口径（空格编 `%20`、值里的 `!'()*` 要删、
  `w_rid` 不能自指）连同"密钥按天缓存"一起交给调用方，用于本库尚未覆盖的 WBI 接口；
  该门面**只算签名、不发请求，也不需要凭据**。同期新增 `Search` / `UserSpace` / `VideoExtra`
  三个数据门面（搜索、用户空间、AI 视频摘要），并补上 README 的**接口来源说明**。
  **门面共 10 个；前 6 个门面的签名与 `throws` 声明一字未改。**
- 0.9.29-beta: **凭据解锁一批（B3.5）** —— 新增第 11 个门面 `Content`（观看历史 / 稍后再看 /
  收藏夹目录，**全 GET 只读**），`UserSpace` 扩 `getUpStat` / `getFollowers` / `getFollowings`，
  `VideoExtra` 扩 `getPlayUrl`（MP4 / DASH 双通道）。
  🔴 本批**全部是普通 GET，不需要 WBI 签名**，所以能插在签名批次之外单独交付；
  前 10 个门面的现有签名**一行未动**，`Content` 交付即纳入冻结契约。
  实测记下的两条坑：`x/player/playurl` 要**去掉 `/wbi/`** 才通（带则 412）；
  `upstat` / 收藏夹目录在**缺凭据时返回 `code=0` 却给空数据**，本库一律按失败抛异常，
  而不是安静地返回一个空结果。
- 0.9.29-beta: **匿名高频补齐（B1）** —— 新增第 12 / 13 / 14 个门面 `Comment`（评论列表）、
  `LiveExtra`（直播拉流 + 主播信息）、`Ranking`（排行榜 + 热门视频）；`VideoExtra` 扩
  `getViewDetail` / `getOnlineTotal`，`UserSpace` 扩 `getRelationStat`。
  **11 项能力实际只发 8 次请求** —— 视频标签、相关推荐、状态数都由 `view/detail` 一次性顺带给出。
  🔴 本批最重要的一条：**排行榜 `ranking/v2` 对 `Referer` 敏感** —— 站根换 `-352`、
  排行榜页或空 `Referer` 才 `code=0`（实测 4 次复现），而"站根"正是全库其它端点的默认值，
  因此该端点单独使用排行榜页 `Referer`；同一分钟对照的 `popular` **不**敏感。
  另有两条"文档说要签名、实测不用"：`x/v2/reply`（评论，文档标 Wbi）与
  `x/player/online/total`（在线观看数，文档标 APP 端）。**门面共 14 个；前 11 个门面一行未改。**
- 0.9.30-beta: **匿名中频补齐（B2）** —— 新增第 15 个门面 `Danmaku`（弹幕）；
  `Comment` 扩 `getSubReplies`（楼中楼）/ `getEmotePanel`（表情包）、`Search` 扩 `getHotSearch`（热搜榜）、
  `Content` 扩 `getFolderInfo` / `getResources`（收藏夹详情与内容）、`LiveExtra` 扩 `getAreaList`（直播分区树）。
  **门面共 15 个；前 14 个门面一行未改。**
  🔴 本批三条值得记的实测：
  ① **弹幕对 `Referer` 完全免疫**（不带 / 站根 / 视频页三格响应字节数完全相同）—— 与排行榜正好相反，
  所以它用全库默认的站根即可，调用方**不必**为它准备 bvid；
  ② **直播分区的 `parent_area_id` 是装饰品** —— 不传 / `=1` / `=2` / `=999`（不存在）返回**逐字相同**的全树
  ⇒ 本库**不暴露**这个参数；
  ③ **表情包需要凭据**（匿名 `code=0` 但 `packages=null`，带凭据才有 68 个包）—— 但**需凭据 ≠ 需签名**。
  另修正两条早先的注解方向：弹幕"需自行 deflate 解压"其实**不必**（HTTP 层已解开），
  收藏夹 `attr` 的公开 / 私密方向原先写反了（`attr=2` 可读、`attr=1` 匿名 `-403`）。
- （**未发布** · B4 收尾）**`Content` 扩 `getArticleInfo(cvId)`（专栏信息）** —— 本门面**唯一免凭据**
  的方法（其余四项都要 Cookie），也是"**只加方法、不加类**"的范例：**门面数仍是 15**。
  🔴 交付前把 B4 的 8 个候选逐条实测了一遍，**除它之外全部确认做不动**（4 项属"入口参数拿不到"、
  2 项属"要先逆向"、2 项属"决策上不做"）⇒ **本库的公开只读面到此基本封顶**。
  ⚠️ 读这个接口最易错的一点：**同一个词在三层里含义不同** —— 全局统计在 `stats.*`
  （`stats.like` 才是"这篇文章有多少赞"），顶层 `like` / `coin` / `favorite` / `attention`
  是**"我"的交互状态**（匿名恒 0）。第 ③ 层的两个布尔**归因不同**：`isAuthor` 跟凭据走
  （可当"已登录"指示器，但**读别人的专栏也是 `true`**），`inList` **只跟"请求有没有带会话指纹"走**
  （零 Cookie `false`、带 `buvid3/4` 就 `true`）⇒ **`inList` 连"已登录"都指示不了**。
  另：正文走 `x/article/view`（实测 `-352` / `-509`，两次都不是 `code=0`），**本库不做**。
- （**未发布**）**JSON 序列化库 `fastjson` 1.2.83 → `fastjson2` 2.0.56** —— 工程债，**不是接口变更**
  （门面一行未改；坐标从 `com.alibaba:fastjson` 换成 `com.alibaba.fastjson2:fastjson2`，
  **两者不同坐标**，下游若还留着 1.x 不会冲突）。
  🔴 迁移里最值得记的一条：**fastjson 1.x 默认"大小写不敏感 + 忽略下划线"，fastjson2 完全没有这个宽容度**
  （实测 2.0.56：`View`→`view`、`goto`→`goTo`、`show_name`→`showName` **一律不匹配，只认同名**）
  ⇒ 凡"字段名与 JSON 键不同名、此前靠宽容度填上"的字段，迁移后会**静默变 null、不抛任何异常**。
  本库这类字段共 **3 处**，已逐一定性：
  ① `HotSearch.goTo`（← `goto`）、② `WatchedShow.Switch`（← `switch`）—— 都是 Java 保留字导致的改名，
  补上 `@JSONField(name = ...)`，并由新增的 `KeyNameMappingGuardTest` 钉住（注解被删不会有编译错误，
  只有真数据能发现）；③ `PlayUrl` 里与 `segment_base` **重复**的 PascalCase 副本 `SegmentBase` —— 删除
  （实测两个字段映射同一 JSON 键时 fastjson2 **不报错**，但**未注解者胜出、另一个恒 null**，
  所以"补个注解让它也填上"走不通）。这是本次**唯一**的公开面删减，全库无调用方读 `getSegmentBase()`。
  ⚠️ 测试套件只暴露了 3 处中的 **1 处**，另两处**没有任何断言** —— 它们是靠一次性脚本做
  "POJO 字段名 vs 夹具 JSON 键（各自去下划线、转小写）比对"扫出来的。以后再换 JSON 库/解析器，
  这一步**不可省**。

> 版本号说明：`0.9.29-beta` 下累积了**四条**（登录 / WBI+搜索 / 凭据解锁 B3.5 / 匿名高频 B1）；
> **`0.9.30-beta` 起单独递增**，其中包含**匿名中频 B2**（已发布版本即 `0.9.30-beta`，范围 = B3.5 + B1 + B2）。
> 上面最后两条（B4 专栏信息、fastjson2 迁移）**尚未发版**，将随下一版一并发布。

## 许可证

    本项目采用 MIT 许可证。

### MIT 许可证


	Copyright (c) 2025 饿死的流浪猫
	
	特此免费授予任何获得本软件及相关文档文件（"软件"）副本的人不受限制地处理本软件的权利，包括但不限于使用、复制、修改、合并、发布、分发、再许可和/或出售本软件副本的权利，以及允许获得本软件的人这样做，但须符合以下条件：
	
	上述版权声明和本许可声明应包含在本软件的所有副本或重要部分中。
	
	本软件按"原样"提供，不提供任何形式的明示或暗示的保证，包括但不限于对适销性、特定用途的适用性和非侵权性的保证。在任何情况下，作者或版权持有人均不对任何索赔、损害或其他责任负责，无论是在合同诉讼、侵权行为或其他方面，由软件或软件的使用或其他交易引起的或与之相关的。

### 附加条款

	1. 归属要求：在使用本项目时，必须明确说明使用了本项目（Bilibili-API）。
	   
	2. 命名区分：如果基于本项目进行二次开发，新项目的命名必须与本项目（Bilibili 	API）有明显区分，以避免混淆。例如，可以使用不同的前缀或后缀，如"XYZ-Bilibili-		API"或"Bilibili-API-Extended"。

## 贡献指南
欢迎提交问题和功能请求。如果您想贡献代码，请先fork本仓库，然后提交pull request。

## 支持和赞助
    如果您喜欢本项目，请给我们一个star。如果您有任何问题或建议，请随时联系我们。
    
    如果想支持我，可以通过以下方式赞助：
> - 支付宝：
    ![zfb.jpg](zfb.jpg)
> - 微信：
   ![wx.png](wx.png)
>   感谢您的支持！