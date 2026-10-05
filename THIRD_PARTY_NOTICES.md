# Third-Party Notices

J2P Mobile 包含以下第三方组件。许可证类型按各组件的官方声明填写；**未能查证的留空，不做推测**。

## Python 依赖（随 APK 分发）

- **jmcomic** — MIT
- **commonx** —
- **requests** — Apache-2.0
- **urllib3** — MIT
- **certifi** — MPL-2.0
- **charset-normalizer** — MIT
- **idna** — BSD-3-Clause
- **Pillow** — HPND (MIT-CMU)
- **chaquopy-libjpeg** —
- **chaquopy-freetype** —
- **pycryptodome** — BSD-2-Clause / Public Domain（双许可）
- **PyYAML** — MIT
- **img2pdf** — LGPL-3.0-or-later
- **pypdf** — BSD-3-Clause

## Android / Kotlin 依赖（随 APK 分发）

- **Kotlin 标准库** — Apache-2.0
- **AndroidX core-ktx** — Apache-2.0
- **AndroidX activity-compose** — Apache-2.0
- **AndroidX lifecycle-runtime-ktx** — Apache-2.0
- **Jetpack Compose（ui / material3）** — Apache-2.0
- **kotlinx-coroutines** — Apache-2.0
- **Chaquopy（Python 运行时与 Gradle 插件）** —

## 构建工具（不随 APK 分发）

- **Android Gradle Plugin** — Apache-2.0
- **Gradle** — Apache-2.0
- **Eclipse Temurin JDK** — GPL-2.0-with-classpath-exception
- **Android SDK Platform / Build-Tools** — Android SDK 许可协议

---

说明：

- 应用**不修改**上述依赖的源码；Python 依赖以 Chaquopy 方式随 APK 打包，Kotlin / AndroidX 依赖以库形式参与链接。
- 上表中留空的组件，其上游仓库或分发渠道未给出明确许可证声明，故此处不填写；如需确认请查阅对应上游项目。
- 本项目自身以 **GPL-3.0-or-later** 授权，见 [LICENSE](LICENSE) 与 [COPYING](COPYING)。