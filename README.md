# J2P Mobile v2.4.4 - jmcomic本子探索、在线浏览与下载工具 📚

一个开源免费的 jmcomic 本子探索、在线浏览、下载与 PDF 合并工具的**安卓版**，
与桌面版 [Jm2PDF](https://github.com/WisadelZ/jm2pdf) 功能对应，界面按手机形态重写。

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Android](https://img.shields.io/badge/Android-7.0%2B-green.svg)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-blue.svg)](https://kotlinlang.org/)

## ✨ 特性

- **探索与详情**：关键词搜索，范围可选全部 / 作品 / 作者 / 标签 / 角色，支持 `+包含` `-排除` 语法，
  结果可排序并按日期筛选；详情页展示封面与信息，可跳转官网、下载、收藏或在线浏览。
- **在线阅读**：详情页点「浏览」即可在线翻页阅读，无需先下载；资源管理器里已下载的 PDF / 图片文件夹
  也能用同一套阅读界面直接翻阅，支持竖式 / 横式切换、页码跳转与手势缩放，点一下画面即可沉浸式全屏。
- **账号、收藏与签到**：登录后可查看头像、昵称、等级经验、收藏数与 J 币；可保存多个账号并一键切换，
  收藏页支持按收藏夹筛选与翻页，详情页可收藏 / 取消收藏，每日签到一键完成。
- **下载队列与任务中心**：一次可加入多个本子排队下载，任务中心显示每个任务的页数进度，
  支持暂停 / 继续 / 取消 / 重试；中断的任务继续时会跳过已下好的图片，退出应用后队列保留、下次可继续。
- **PDF 合并与元数据**：可选将每个章节合并为 PDF，并自动写入标题、作者、标签、ID、页数、章节等信息。
- **资源管理器**：按漫画归组浏览本地下载目录，支持搜索、排序、批量打开或删除（移入回收站），
  选中 PDF 时展示其元数据。
- **邮件推送**：下载完成后可将生成的 PDF 发送到指定邮箱（兼容 QQ、163 等主流邮箱）。
- **界面**：内置简体中文 / 繁体中文 / 英文，支持浅色 / 深色 / 跟随系统外观，切换后立即生效。

## 🚀 快速开始

### 方式一：使用预编译版本（推荐）

1. 访问 [Releases](https://github.com/WisadelZ/J2P-Mobile/releases) 页面
2. 按手机架构选择对应的安装包并安装（需 Android 7.0 及以上）：
   - `J2P-Mobile-v2.4.4-arm64-v8a.apk` —— 仅 arm64-v8a（绝大多数真机选它）
   - `J2P-Mobile-v2.4.4-x86_64.apk` —— 仅 x86_64
   - `J2P-Mobile-v2.4.4.apk` —— 双架构（文件名不带架构名）

> **如果你清楚你的手机架构，请选择对应架构安装包来安装，因为体积最小；如果你不清楚，当然也可以直接下载不带架构名的安装包，只是体积略大，无伤大雅。**

### 方式二：从源码构建

```bash
# 克隆仓库
git clone https://github.com/WisadelZ/J2P-Mobile.git
cd J2P-Mobile

# 一次构建即产出 3 个包（体积：单架构 < 双架构）：
#   app-release-arm64-v8a.apk   仅 arm64-v8a
#   app-release-x86_64.apk      仅 x86_64
#   app-release.apk             双架构（无后缀）
./gradlew assembleRelease

# 调试包同理，一次也产出 3 个（文件名以 app-debug 开头）
./gradlew assembleDebug
```

> **如果你清楚你的手机架构，请选择对应架构安装包来安装，因为体积最小；如果你不清楚，当然也可以直接下载不带架构名的安装包，只是体积略大，无伤大雅。**

构建依赖：JDK 17、Android SDK（compileSdk 37）、Chaquopy 17（内嵌 CPython 3.11）。
Chaquopy 需要构建机上有同主次版本的 Python；可用 `-Pchaquopy.buildPython=<路径>` 指定，
或写进 `local.properties` 的 `chaquopy.buildPython`（该文件不入库）。

## 📖 使用说明

### 基本流程

启动后进入「探索」页，界面底部四个入口依次为：**探索 / 下载 / 资源管理器 / 账号**。

1. **搜索**：在搜索框输入关键词，或直接输入本子 ID（如：`1462837`），回车开始检索
2. **进详情**：点封面或标题进入本子详情页
3. **下载**：详情页点「加入下载列表」，或到「下载」页输入一个 / 多个本子 ID（逗号或空格分隔）
4. **看进度**：「下载」页可展开「下载日志」；点进「任务中心」看每个任务的页数与状态

### 搜索功能

- 搜索范围：全部 / 作品 / 作者 / 标签 / 角色；支持 `+关键词`（必须包含）与 `-关键词`（排除）
- 结果支持按最新 / 最多点击 / 最多图片排序，并可组合日期筛选
- 结果每行 3 本，封面固定 3:4；点封面或标题都能进入详情

### 探索与本子详情

详情页展示大封面、作者、标签、点赞与观看数，底部提供：

- **浏览**：在线翻页阅读（无需下载）
- **加入下载列表**：进入下载队列
- **收藏 / 取消收藏**：选择要加入的收藏夹
- **打开官网**：用浏览器打开本子页面

### 在线阅读与本地浏览

- 阅读器默认**竖式连续翻页**，可在底部切换为**横式单页**；支持进度滑杆与页码跳转
- **双指捏合缩放**：竖式下整条图片流一起缩放，页与页保持连续、翻页不受影响；
  缩到接近初始大小松手会自动吸附回初始大小
- **点一下画面**收起界面（顶栏 / 控制栏 / 系统栏）进入沉浸式全屏，再点一下唤回

### 账号、收藏与签到

- 登录后可查看头像、昵称、等级称号、经验进度、收藏数与 J 币
- 可保存多个账号：登录页下方「已登录账号」列表点选即可切换，点叉可清除该账号
- 收藏页支持按收藏夹筛选与翻页；每日签到一键完成并展示当月 / 连续签到与当天奖励
- 账号密码在本机**加密保存**（见 [SECURITY.md](SECURITY.md)），退出登录即清除

### 下载队列与任务中心

- 「下载」页可一次加入多个本子排队下载，任务中心里每个任务显示状态、进度条与页数进度
- 单条操作：暂停 / 继续 / 取消 / 重试 / 移除 / 打开文件夹；另有「暂停全部 / 继续全部」
- 中断的任务继续时会跳过已下好的图片；队列会持久化，退出应用后下次可继续

### 资源管理器

- 按漫画归组浏览本地下载目录，支持按名称 / 作者 / 标签 / 本子 ID 搜索，按名称 / 时间 / 大小 / 页数排序
- 选中后可 打开 / 浏览 / 查看元数据 / 删除（移入回收站，可从回收站恢复）

### 邮件推送（可选）

在「设置」中填写 SMTP 服务器与授权码后，下载完成的 PDF 可发送到指定邮箱；收件人留空则发给自己。

### 设置

「账号」页右上角的小齿轮进入设置，包含：配置信息、外观（浅色 / 深色 / 跟随系统）、语言、
账号设置（自动登录并签到）、缓存管理、配置导入导出，以及关于信息（版本 / 作者 / 项目地址 / 许可）。

## 📁 项目结构

```
J2P-Mobile/
├── settings.gradle.kts / build.gradle.kts / gradle.properties   # 构建配置
├── gradlew / gradlew.bat / gradle/wrapper/                      # Gradle Wrapper
├── LICENSE                     # 许可证指引（GPLv3）
├── COPYING                     # GNU GPLv3 许可证全文
├── THIRD_PARTY_NOTICES.md      # 第三方依赖许可声明
├── README.md                   # 项目说明
├── CHANGELOG.md                # 更新日志
├── SECURITY.md                 # 安全策略（凭据处理、漏洞报告）
├── .gitignore                  # Git 忽略规则
├── docs/                       # 文档与设计素材
│   └── artwork/icon.png        # 图标源图（不打包进 APK）
└── app/                        # 应用模块
    ├── build.gradle.kts        # Compose + Chaquopy + 依赖清单
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/                # 图标、矢量图标、三语文案
        ├── java/com/j2pmobile/android/     # Kotlin：桥接层 / 平台层 / UI
        │   ├── PythonBridge.kt / ApiBridge.kt     # Kotlin ↔ Python 调用入口
        │   ├── LogBridge.kt / QueueBridge.kt       # Python → Kotlin 日志与队列通知
        │   ├── ImageBridge.kt                      # 原生图片解码 + 解扰
        │   ├── AccountCrypto.kt                    # 账号凭据加密（Android Keystore）
        │   ├── Platform.kt / FileOpener.kt / RecycleBin.kt  # 存储 / 打开 / 回收站
        │   ├── DownloadService.kt                  # 下载前台服务与进度通知
        │   └── ui/                                 # 页面（Compose）
        └── python/             # Business core（随 APK 打包）
            ├── bridge.py       # Python 门面：所有对外函数
            ├── core/           # 站点协议、下载编排、PDF、账号、浏览等
            ├── utils/          # 通用辅助
            └── curl_cffi/      # 导入期占位模块
```

安卓端按「**UI / 平台 / 业务**」三层拆分：UI 与平台能力用 Kotlin 原生实现，
只有站点协议与下载编排等必须留在 Python 的部分用 Chaquopy 内嵌运行（同进程、直接函数调用）。

## ⚙️ 配置说明

首次运行时会自动生成 `conf.yml`（位于应用私有目录 `files/config/`），可在应用内设置页调整：

```yaml
app:
  download_dir: ./download      # 下载目录（相对应用数据目录解析）
  to_pdf: true                  # 是否合并为 PDF
  thread_image: 30              # 图片并发数
  thread_photo: 16              # 章节并发数
  task_concurrency: 2           # 同时下载几个本子（1~4）
  theme_mode: dark              # 界面主题：light / dark / system（默认深色）
  language: zh_cn               # 界面语言：zh_cn / zh_tw / en
  auto_login: false             # 启动时自动登录并签到

mail:
  enable: false                 # 是否启用邮件
  server: smtp.qq.com           # SMTP 服务器
  port: 465                     # 端口
  sender: ''                    # 发件邮箱
  password: ''                  # 授权码
  receiver: ''                  # 收件邮箱（留空=自己）
```

登录后的站点账号**不写入 `conf.yml`**，而是加密保存在应用私有目录的 `account.dat`，
密钥由 Android 系统密钥库（Android Keystore）保管、不可导出；换机或清除应用数据后无法解密。
凭据处理与安全边界的完整说明见 [SECURITY.md](SECURITY.md)。

## 🔧 技术栈

- **语言**：Kotlin 2.4（UI / 平台层）+ Python 3.11（业务核心，Chaquopy 17 内嵌于 APK）
- **界面**：Jetpack Compose（Material 3），底部导航 + 页面栈路由，三语与明暗主题
- **核心库**：jmcomic（禁漫客户端，站点协议与图片解密）、img2pdf + pypdf（PDF 合并与元数据）、
  Pillow（图像处理）、pycryptodome（桌面端账号加密回退）、PyYAML（配置序列化）
- **原生能力**：Android Keystore（账号加密）、BitmapFactory 解码 + 原生解扰、前台服务与通知、
  FileProvider 打开文件、应用内回收站软删除
- **构建**：AGP 9.2 + Gradle 9.4 + JDK 17；`minSdk 24 / targetSdk 36`，
  每次构建按 ABI 拆分，一次产出 **arm64-v8a / x86_64 / 双架构** 共 3 个包（单架构带 ABI 后缀，双架构不带）

## 🙏 反馈与建议

**本仓库只接受 Issue**：有功能需求或问题反馈，请到
[Issues](https://github.com/WisadelZ/J2P-Mobile/issues) 提交。

**不接受 Fork 与 Pull Request** —— 本仓库不合并第三方改动，问题与需求统一在 Issue 里沟通，
由维护者处理。

> 许可证层面与主项目一致：本项目采用 GPLv3（允许衍生作品），
> 你仍然可以自由地自行 Fork、修改与再分发，但**对外发布的修改版须同样以 GPLv3 开源**。

## 📄 许可证

This project is licensed under the GNU General Public License v3.0 or later.

See the file COPYING for the full license text.

第三方依赖与许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 🙏 致谢

- [Jm2PDF](https://github.com/WisadelZ/jm2pdf)：本项目的桌面版，安卓端的交互与业务逻辑以其为基准
- jmcomic 禁漫客户端库
- Chaquopy（在 APK 内嵌入 CPython）、Jetpack Compose 与 Android 开源社区

---

**注意**：本工具仅用于学习研究，请遵守网站的使用条款，尊重版权和创作者权益。