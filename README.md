# Threxo Zip

IntelliJ IDEA 项目视图右键压缩/解压插件，交互对齐 Mac 版 Threxo。

## 功能

| 操作 | 行为 |
| --- | --- |
| 右键文件/文件夹 → **压缩为 ZIP** | 在所在目录生成 ZIP。单个对象使用自身名称，多个对象命名为 `归档`；重名自动追加序号（`a.zip` → `a 2.zip`），不覆盖已有文件 |
| 右键压缩包 → **解压到当前目录** | 解压到压缩包所在目录。同名文件自动改名（`a.txt` → `a_1.txt`），目录按层级合并，绝不静默覆盖 |

其他行为：

- 默认排除 `.DS_Store`、`__MACOSX`、`._*`、`Thumbs.db`、`desktop.ini` 等系统辅助文件。
- ZIP 文件名使用 UTF-8；解压 Windows 下生成的 GBK 文件名压缩包时会自动识别编码。
- 压缩/解压均在后台执行，带进度、可取消，完成后弹出通知并刷新项目视图。
- 解压时校验路径，拒绝 `../`、绝对路径等越界条目，跳过符号链接。

## 支持格式

| 操作 | 格式 |
| --- | --- |
| 压缩 | ZIP（当前固定） |
| 解压 | ZIP、JAR、WAR、EAR、7Z、TAR、TAR.GZ/TGZ、TAR.BZ2/TBZ2、TAR.XZ/TXZ、GZ、BZ2、XZ |

暂不支持加密压缩包。

## 多语言

菜单、描述、进度与通知文案按 IDE 当前语言显示：

- 启用中文语言包：简体中文
- 其他情况：英文
- 平台语言 API 不可用时：按操作系统语言

## 环境要求

- IntelliJ IDEA 2023.2 – 2024.2（build 232 – 242.*）
- JDK 17

## 构建与安装

```bash
# 构建插件安装包
./gradlew buildPlugin
```

产物：`build/distributions/Threxo-Idea-Plugin-1.0.1.zip`

安装：`Settings → Plugins → ⚙ → Install Plugin from Disk...`，选择上述 ZIP 后重启 IDE。

调试运行：

```bash
./gradlew runIde
```

## 项目结构

```text
src/main/java/com/qujunxian/threxoideaplugin/
├── action/   # 右键菜单动作
├── core/     # 压缩/解压、后台任务、格式识别
└── i18n/     # 多语言资源加载

src/main/resources/
├── META-INF/plugin.xml
└── messages/ # 中英文文案
```

## 许可证

本项目采用 [MIT](LICENSE) 许可证。

---

## English

A lightweight IntelliJ IDEA plugin that adds **Compress to ZIP** and **Extract Here** to the Project View context menu.

- Compress files/folders into a ZIP archive in the same directory; existing archives are never overwritten (`a.zip` → `a 2.zip`).
- Extract archives into their own directory; conflicting files are renamed (`a.txt` → `a_1.txt`) instead of overwritten.
- Supports extracting ZIP/JAR/WAR/EAR, 7Z, TAR, TAR.GZ/TGZ, TAR.BZ2/TBZ2, TAR.XZ/TXZ, GZ, BZ2 and XZ.
- Runs in the background with progress, cancellation, notifications and VFS refresh.
- UI follows the IDE language (English/Chinese), falling back to the system language if needed.

Requirements: IntelliJ IDEA 2023.2–2024.2, JDK 17.

```bash
./gradlew buildPlugin   # build the plugin ZIP
./gradlew runIde        # run a sandbox IDE with the plugin
```

Licensed under the [MIT License](LICENSE).
