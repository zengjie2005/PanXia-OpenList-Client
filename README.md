# 盘匣（PanXia）

一个直连自己 [OpenList](https://github.com/OpenListTeam/OpenList) 服务的安卓客户端。
**零第三方依赖**（纯 Android Framework + Java，连 AppCompat 都没有），APK 约 240 KB。

支持同时保存**多台服务器**随时切换；账号密码只存在本机。

> **作者**：zhj　|　**版本**：1.0　|　**许可证**：MIT　|　**服务端**：OpenList v4 API（在 v4.2.7 上验证）

盘匣是一个独立的第三方客户端，与 OpenList 官方及其任何官方移动端都不是同一个项目。

---

## 特性

| 分类 | 说明 |
| --- | --- |
| 多服务器 | 保存任意多台 OpenList，随时切换；每台各自记住地址、账号、密码、登录态和上次所在目录 |
| 登录 | `http://` 或 `https://` + 域名 / IP + 端口，可记住密码、可忽略自签名证书 |
| 浏览 | 目录列表、胶囊面包屑、返回上级、下拉刷新、按名称/大小/时间排序（含倒序）、显示隐藏文件、当前目录内筛选 |
| 预览 | **点文件直接打开**：图片缩放浏览、视频/音频在线播放、文本与代码查看、Markdown 渲染；PDF / Office 交给系统查看器 |
| 编辑 | 文本 / 代码 / Markdown **在线编辑并保存回服务器**，保留原文件编码（UTF-8 / GBK / BOM） |
| 上传 | 系统文件选择器多选上传，实时进度与百分比，可随时取消；同名文件先确认 |
| 下载 | 保存到 `下载/PanXia/`，带进度可取消（Android 10+ 走 MediaStore，无需存储权限） |
| 管理 | 新建文件夹、重命名、删除、复制到…、移动到…、详情、复制直链 |
| 批量 | 长按进入多选，全选 / 复制 / 移动 / 删除 |
| 外观 | 主题跟随系统 / 浅色 / 深色，自适应图标（含 Android 13 主题图标），按键触感反馈可关 |

## 多服务器

- 登录页顶部列出已保存的服务器，点一下即切换，也可以「＋ 添加新服务器」。
- 主界面右上角 **⋮ → 切换服务器** 进入服务器列表：切换 / 重命名 / 测试连接 / 删除。
- 设置页「当前服务器」右侧的 **切换 / 管理** 是同一个入口。
- 每台服务器的登录态、密码、上次停留的目录都是独立的；切回来还是原来的位置，不用重新登录。
- 地址相同的服务器不会重复添加，再次登录会覆盖更新那一条记录。

## 安装

1. 从 [Releases](../../releases) 下载 `PanXia-1.0.apk`（或自行构建，见下）。
2. 允许「安装未知来源应用」后安装。
3. 打开 App，填写：
   - 服务器地址：`http://192.168.1.10:5244` 或 `https://pan.example.com`（不写 `http://` 会自动补上）
   - 用户名 / 密码：OpenList 后台账号
4. 点「登录」；也可以先点「测试连接」确认地址通不通。

> 提供的 APK 使用 debug 签名，未配置正式签名证书，安装时可能提示未知来源。

## 构建

要求：JDK 17、Android SDK（compileSdk 35）。不需要 Android Studio，也不需要任何第三方库。

```bash
# 1) 配置 SDK 路径（local.properties 不进版本库）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2) 构建
./gradlew assembleDebug        # Linux / macOS
gradlew.bat assembleDebug      # Windows

# 产物
app/build/outputs/apk/debug/app-debug.apk
```

如果 `gradlew` 没有执行权限（从 Windows 提交的文件常常会丢），先 `chmod +x gradlew`，或直接用 `sh gradlew assembleDebug`。

版本号规则：`versionName` 是 `主.次`，`versionCode = 主 × 10000 + 次 × 100 + 修订`（1.0 → 10000）。

## 项目结构

```
app/src/main/
├── java/com/zhj/panxia/
│   ├── Api.java                 OpenList v4 HTTP 接口（HttpURLConnection + org.json）
│   ├── Store.java               本机配置：多服务器列表 + 全局偏好
│   ├── LoginActivity.java       登录 / 选择或新增服务器
│   ├── ServersActivity.java     服务器管理（切换 / 重命名 / 测试 / 删除）
│   ├── MainActivity.java        文件列表、面包屑、上传、多选、排序、筛选
│   ├── ImageActivity.java       图片查看：缩放手势 + 翻页动画
│   ├── ZoomImageView.java       缩放 / 拖动 / 翻页手势
│   ├── MediaActivity.java       视频音频播放（方向由按钮控制）
│   ├── TextViewerActivity.java  文本查看与在线编辑保存
│   ├── DirPickerActivity.java   复制 / 移动的目标目录选择
│   ├── SettingsActivity.java    设置
│   ├── Bars.java                系统栏 insets 适配（edge-to-edge）
│   ├── Theme.java / Ui.java     主题与界面工具
│   ├── Feedback.java            触感反馈
│   ├── PullRefreshLayout.java   下拉刷新容器
│   ├── Net.java / Dl.java       图片解码、下载
│   └── LocalProvider.java       content:// 提供者（Android 7+ 禁 file://）
└── res/                         布局、矢量图标、主题
```

## 接口对照

| 用途 | 接口 |
| --- | --- |
| 登录 | `POST /api/auth/login` → `data.token`，后续请求头 `Authorization: <token>`（不带 `Bearer`） |
| 探测 | `GET /api/public/settings` |
| 当前用户 | `GET /api/me` |
| 列目录 | `POST /api/fs/list`（`per_page=0` 一次列全） |
| 文件详情 / 直链 | `POST /api/fs/get` → `data.raw_url` |
| 上传 / 覆盖写入 | `PUT /api/fs/put`，路径放在 `File-Path` 头（URL 编码） |
| 新建 / 删除 / 重命名 | `POST /api/fs/mkdir` / `/api/fs/remove` / `/api/fs/rename` |
| 复制 / 移动 | `POST /api/fs/copy` / `/api/fs/move` |
| 目录树 | `POST /api/fs/dirs` |

预览与播放直接用 `raw_url`（带签名的直链），不需要额外认证头。
路径编码等价 `encodeURIComponent`（空格是 `%20` 而不是 `+`），中文与空格文件名已做往返验证。

## 已知限制

- **没有服务端搜索**：部分版本（如 v4.2.7）的 `/api/fs/search` 返回 404，所以筛选只在「当前目录」内做。
- **上传同名文件会覆盖**：会先弹确认，确认后是覆盖写入，没有「保留两者」。
- **复制/移动不合并冲突**：目标已存在同名文件时服务端会报错，需要先重命名。
- 不支持 OpenList 的分享链接管理、回收站、WebDAV、离线下载任务。
- 直链带签名，有效期由服务端决定；预览提示 403 时刷新目录重新取直链即可。
- GIF 只显示第一帧（系统 `BitmapFactory` 行为），要看动图可用「用浏览器打开」。
- PDF / Office 依赖手机里已安装的查看器。
- 超过 2 MB 的文本只读，避免把截断内容写回原文件。
- 按手机竖屏设计，未做平板 / 横屏大屏适配。

## 隐私与安全

- 账号密码只存在手机本地 `SharedPreferences`（异或 + Base64 **混淆**，不是加密），不会上传到任何第三方服务器。
- 登录 token 同样只存本地；「退出登录」只清当前服务器的 token 与密码，服务器条目保留。
- 没有统计上报，没有第三方 SDK，没有广告，不申请多余权限。
- 「忽略证书错误」只对开启该选项的那台服务器生效；打开后该连接不再校验证书，公网环境请谨慎。

## 更新日志

### 1.0（首个公开版本）

- 多服务器：保存、切换、重命名、测试、删除，各自独立的登录态与浏览位置
- 文件浏览：面包屑、下拉刷新、排序、隐藏文件、目录内筛选、长按多选
- 预览：图片（缩放手势 + 跟手翻页过渡）、视频 / 音频、文本、Markdown，PDF / Office 交给系统查看器
- 文本在线编辑并保存，保留原编码与 BOM
- 上传 / 下载 / 新建 / 重命名 / 删除 / 复制 / 移动 / 详情 / 直链
- 主题跟随系统或强制浅色、深色；触感反馈可关
- 视频播放：信息栏跟随播放控制栏显隐，横竖屏由按钮切换
- Android 15 edge-to-edge 与系统栏适配

## 关于

| 项 | 内容 |
| --- | --- |
| 名称 | 盘匣（PanXia） |
| 作者 | **zhj** |
| 版本 | 1.0（versionCode 10000） |
| 包名 | `com.zhj.panxia` |
| 运行环境 | Android 7.0（API 24）及以上，targetSdk 35 |
| 技术栈 | 纯 Android Framework + Java，零第三方依赖 |
| 许可证 | [MIT](LICENSE) |
| 兼容服务端 | OpenList v4 API（在 v4.2.7 上验证） |

**免责声明**：本项目是个人开发的第三方客户端，与 OpenList 官方无关。请自行评估数据安全与使用风险，
因服务端配置、网络环境或本程序缺陷造成的任何损失，作者不承担责任。

如果对你有帮助，欢迎 Star；有问题请提 Issue。

## 致谢

- [OpenList](https://github.com/OpenListTeam/OpenList) —— 自建文件列表程序，本项目的服务端
- 感谢 Android 系统提供的 `MediaController`、`VideoView`、`GestureDetector` 等基础组件
