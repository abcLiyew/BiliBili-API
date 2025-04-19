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
- Chrome浏览器（用于动态截图功能）

## 安装方法

### Maven

```xml
<dependency>
    <groupId>com.esdllm</groupId>
    <artifactId>bilibili-api</artifactId>
    <version>0.9.13.1-beta</version>
</dependency>
```
**由于目前项目尚未发布到中央仓库，需要有以下两种方式导入本地仓库：**
- 1 克隆仓库到本地，然后执行以下命令：
```bash
mvn install
```
- 2 在[Release](https://github.com/abcLiyew/BiliBili-API/releases/tag/beta)中下载最新版本的jar包，并将其复制到本地Maven仓库中。
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
<span style="color=red;">由于动态截图功能需要Chrome浏览器，请先安装Chrome浏览器。并且获取动态列表和图片耗时较长，建议在多线程中获取动态列表和图片。
</span>
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

## 版本历史
- 0.9.13.1-beta: 初始版本

## 许可证

    本项目采用 MIT 许可证。

### MIT 许可证

    Copyright (c) 2025 饿死的流浪猫

    特此免费授予任何获得本软件及相关文档文件（"软件"）副本的人不受限制地处理本软件的权利，包括但不限于使用、复制、修改、合并、发布、分发、再许可和/或出售本软件副本的权利，以及允许获得本软件的人这样做，但须符合以下条件：

    上述版权声明和本许可声明应包含在本软件的所有副本或重要部分中。

    本软件按"原样"提供，不提供任何形式的明示或暗示的保证，包括但不限于对适销性、特定用途的适用性和非侵权性的保证。在任何情况下，作者或版权持有人均不对任何索赔、损害或其他责任负责，无论是在合同诉讼、侵权行为或其他方面，由软件或软件的使用或其他交易引起的或与之相关的。

### 附加条款

1. **归属要求**：在使用本项目时，必须明确说明使用了本项目（Bilibili-API）。

2. **命名区分**：如果基于本项目进行二次开发，新项目的命名必须与本项目（Bilibili API）有明显区分，以避免混淆。例如，可以使用不同的前缀或后缀，如"XYZ-Bilibili-API"或"Bilibili-API-Extended"。

## 贡献指南
欢迎提交问题和功能请求。如果您想贡献代码，请先fork本仓库，然后提交pull request。

## 支持和赞助
    如果您喜欢本项目，请给我们一个star。如果您有任何问题或建议，请随时联系我们。

    如果想支持我，可以通过以下方式赞助：
> - 支付宝：
    ![zfb.jpg](zfb.jpg)
> - 微信：
   ![wx.png](wx.png)
> 感谢您的支持！