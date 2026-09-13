# Bilibili API

## 项目简介

Bilibili API 是一个用于获取哔哩哔哩（Bilibili）平台数据的Java库。该项目提供了一系列API接口，可以获取用户信息、动态内容、直播信息等数据。

## 功能特性

- **用户信息获取**：获取用户名称、头像、等级、签名、粉丝数等基本信息
- **动态内容获取**：获取用户动态列表、动态详情、动态图片等
- **直播信息获取**：获取用户直播间状态、直播间信息等
- **视频信息获取**：获取用户视频投稿数量、视频详情等

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
- 2 在[Release](https://github.com/abcLiyew/BiliBili-API/releases/tag/beta)中下载最新版本的jar包，并将其复制到本地Maven仓库中。
在你的Maven项目中，将以上代码添加到`pom.xml`文件的`<dependencies>`标签内，即可引入本库。
```xml
<dependency>
    <groupId>com.esdllm</groupId>
    <artifactId>bilibili-api</artifactId>
    <version>0.9.27-beta</version>
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
## 数据模型
项目中包含多种数据模型，用于表示不同类型的数据。
- `Card`: 用户卡片信息
- `BilibiliCardResp`: B站用户的名片响应
- `BilibiliDynamicResp`: B站用户的动态响应
- `DynamicInfo`: 动态信息
- `BilibiliLiveResp`: B站用户的直播响应
  
## 注意事项
1. 请注意，使用本库时，请遵守哔哩哔哩的API使用规则和限制。
2. 本项目仅供学习参考，请勿用于商业用途。
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
- 其它端点（`Live` / `CardInfo` / `BilibiliClient`）匿名可用，**只有动态列表需要 Cookie**。

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