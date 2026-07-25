# Veliora（Android TV）

<div align="center">
  <img src="image/logo.png" alt="Logo" width="120">
  <br>
  <p><strong>影视聚合搜索工具 · 为电视遥控器而生</strong></p>
</div>

## 📺 项目简介

本项目是一个 **Android TV 端的影视聚合搜索工具**：把聚合搜索与播放做成可以完全用遥控器操作的电视界面。Netflix 风格的沉浸式 UI，原生 ExoPlayer 播放器支持 4K 硬解直通，内置本地代理无需额外后端。

**本工具本身不预置、不存储、不分发任何影视内容源**。要使用采集功能，需由用户自行在「设置 → 自定义接口」中添加符合苹果 CMS V10 API 规范的接口地址。这与浏览器、下载工具等通用工具的定位一致——软件提供能力，内容来源与使用行为由用户自行负责。

**本项目基于 [LibreSpark/LibreTV](https://github.com/LibreSpark/LibreTV) 开发**（Apache License 2.0），复用其多源聚合搜索、苹果 CMS API 适配、豆瓣推荐等核心能力，在此之上进行了电视端的整体改造。感谢原项目及其上游 [bestK/tv](https://github.com/bestK/tv) 的所有贡献者。

## ✨ 相比上游（LibreTV）的改造

### 📺 电视端整体改造

- **原生 Android TV 工程**（`android/`）：WebView 承载页面 + 原生 ExoPlayer 播放，可从源码构建为单个 APK
- **遥控器交互层**（`js/tv-remote.js`）：方向键焦点导航（几何最近邻算法，适配任意布局）、Netflix 风格主页（导航栏 + Billboard 焦点跟随 + 横向内容排）、返回键两段式回顶
- **内置本地代理**（`ProxyHandler.kt`）：原生实现上游 `server.mjs` 的 `/proxy/` 端点（UA 伪装、豆瓣图床 Referer、响应头过滤），APK 单机可用，无需部署后端

### ▶️ 原生播放器（替代 WebView + hls.js）

- **4K / HEVC 硬解直通**：HLS 由 Media3 原生解析，能力取决于芯片而非 WebView；异常流自动回退网页播放器兜底
- **清晰度切换**：多码率源支持手动锁档并记忆偏好，菜单键 / 长按 OK 呼出
- **缓冲加载动画 + 实时网速**：起播、拖动、卡顿时显示转圈与下行网速，不再黑屏无反馈
- **断点续播**：按集记忆播放进度，看完自动清除；15 秒快进/快退、上一集/下一集均适配遥控器
- **m3u8 广告过滤**：原生层 `M3u8AdFilterDataSource` 与上游 `js/player.js` 的过滤逻辑对齐

### 🔍 搜索与内容发现

- **搜索结果按片名聚合**：一部影片一张海报卡（不再是每个源一条的重复列表），进详情后再切换播放源
- **拼音／首字母／英文名搜索**：软键盘只有字母数字，而采集源只按中文片名匹配。输入全拼（qingyunian）、首字母（qyn）或英文原名（interstellar）会先联想成中文片名候选（爱奇艺 + 百度 + 豆瓣三路互补），按「搜索」自动用最可信的中文片名去搜，候选词也列成药丸可直接点选
- **详情页渐进式加载**：各源并行搜索，先返回先显示，无需等全部源超时
- **发现页多维筛选**：豆瓣 形态 × 类型 × 地区 × 标签 组合筛选，焦点滚到底自动追加下一批
- **完整功能对齐主站**：搜索、历史、继续观看、设置（源管理/自定义 API/广告过滤/黄标过滤）均可用遥控器操作

### 🛠️ 工程化

- **一键脚本**：本地启动/停止、从源码构建 APK（macOS/Linux/Windows）、ADB 安装到电视

## 🚀 快速开始

> 本仓库只提供源码。请自行构建，并在使用前自行添加采集源。

### 从源码构建 APK

前置：JDK 17/21、Android SDK（脚本会给出安装提示）

```bash
# macOS / Linux
scripts/build-apk.sh            # 构建 release APK
scripts/build-apk.sh --clean    # 清理后重新构建

# Windows
powershell -ExecutionPolicy Bypass -File scripts\build-apk.ps1
```

产物：`android/app/build/outputs/apk/release/app-release.apk`（自动生成本机自签名 keystore）

### 安装到电视

```bash
# 方式一：ADB 网络安装（电视需开启「ADB 网络调试」，与本机同一局域网）
scripts/install-tv.sh <电视IP>

# 方式二：APK 拷入 U 盘，在电视的文件管理器中安装
```

### 本地 Web 调试

```bash
# macOS / Linux
scripts/start.sh          # 启动（--dev 为 nodemon 热重载）
scripts/stop.sh           # 停止

# Windows
scripts\start.bat
scripts\stop.bat
```

访问 `http://localhost:8080`（端口可在 `.env` 中通过 `PORT` 修改）。浏览器打开即为电视版 UI，可用键盘方向键模拟遥控器。

> 简单静态服务器（如 `python -m http.server`）没有代理端点，视频无法播放；请使用上述脚本或 `npm run dev`。

### 添加采集源

首次启动后源列表为空。在应用「设置 → 自定义接口」中添加符合苹果 CMS V10 API 规范的接口地址：

- 接口地址示例：`https://example.com/api.php/provide/vod`

源的选择与合法性由使用者自行负责。

### 公网部署（可选）

保留了上游的全部部署能力（Vercel / Netlify / Cloudflare / Docker），方法见 [LibreSpark/LibreTV 部署文档](https://github.com/LibreSpark/LibreTV#-快速部署)。公网部署时**必须设置 `PASSWORD` 环境变量**，密码门与代理鉴权将正常生效，避免向不特定公众开放。

## 📁 项目结构

```
├── android/                 # Android TV 原生工程（Kotlin + Media3 ExoPlayer）
│   └── app/src/main/java/org/veliora/television/
│       ├── MainActivity.kt           # WebView 容器 + JS Bridge
│       ├── PlayerActivity.kt         # 原生 ExoPlayer 播放器
│       ├── ProxyHandler.kt           # 内置本地代理
│       └── M3u8AdFilterDataSource.kt # m3u8 广告过滤
├── js/tv-remote.js          # 遥控器交互层（电视版 UI 核心）
├── js/customer_site.js      # 采集源登记（默认为空，用户自行添加）
├── css/tv.css               # 电视版样式
├── index.html / player.html # 页面（同时用于 Web 与 APK assets）
├── js/ libs/ image/         # 上游核心逻辑（聚合搜索/播放/豆瓣/代理鉴权）
├── scripts/                 # 一键脚本：start/stop/build-apk/install-tv
└── server.mjs               # 本地/公网部署用 Node 服务（含代理端点）
```

## 🎮 遥控器操作

- **方向键**：焦点导航　**OK**：确认/播放
- **返回**：逐级返回；列表页两段式（先回顶部，再返回）；发现页呼出筛选浮层
- **播放器内**：左右快进/快退，OK 暂停/继续，上下呼出选集

## 🔌 API 兼容性

与上游一致，支持标准苹果 CMS V10 API。在设置面板「自定义接口」中添加：

- 接口地址：`https://example.com/api.php/provide/vod`

## 🛠️ 技术栈

- 前端：HTML5 + CSS3 + JavaScript（ES6+）、Tailwind CSS、HLS.js
- Android：Kotlin、WebView（WebViewAssetLoader）、Media3 ExoPlayer
- 服务端（Web 部署时）：Node.js / Serverless Functions HLS 代理

## 🔄 与上游的关系

本仓库是独立仓库，未保留上游提交历史。如需参考或移植上游 [LibreSpark/LibreTV](https://github.com/LibreSpark/LibreTV) 的后续更新，请手动对比差异后按需合入。

## ⚠️ 免责声明

本项目是一个**通用的影视聚合搜索工具**，其本身不预置、不存储、不上传、不分发任何视频内容或内容源。软件默认不包含任何采集源，所有内容来源均由使用者自行添加，搜索结果来自使用者所配置的第三方 API 接口。

本项目仅供学习、研究与个人使用，**请勿用于商业用途或搭建面向公众的服务**。使用者须自行确保所添加的内容源及使用行为符合所在地法律法规；因使用本项目（包括但不限于自行添加的任何内容源）所产生的一切后果，由使用者自行承担，项目开发者不承担任何责任。

如你是版权方并认为本工具的某项功能不当，请通过 Issue 联系。

## 📄 许可证

[Apache License 2.0](LICENSE)。本项目基于 [LibreSpark/LibreTV](https://github.com/LibreSpark/LibreTV)（Copyright LibreTV Team, Apache-2.0）修改，主要修改内容见上文「相比上游（LibreTV）的改造」一节。
