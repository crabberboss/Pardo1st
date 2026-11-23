# 帕朵一号 / Novel Memory

长篇小说知识库、对话、改写与阅读工具，包含 Windows 桌面端和 Android 端源码。

- `desktop/`：Python 桌面端源码。运行 `python app.py` 可启动本地网页界面；`python desktop_launcher.py` 使用独立窗口（需安装 `pywebview` 与 Windows WebView2）。
- `android/`：Android 原生外壳、WebView 页面与单元测试。使用 JDK 17、Android SDK 35、Gradle 8.11.1 构建。

本仓库**不包含** API Key、个人提示词配置、知识库 SQLite、导入的小说、聊天记录、构建缓存或安装包。运行时会在本机创建数据目录。不要把本机的 `data/`、`provider_configs/` 或数据库文件提交到公开仓库。

桌面端基础功能主要使用 Python 标准库；独立窗口需 `pywebview`。测试：在 `desktop/` 中运行 `python -m unittest test_scene_workspace test_epub_import`。Android 测试与构建：在 `android/` 中运行 `gradle :app:testDebugUnitTest :app:assembleDebug`。

应用完全免费、非商业，仅供学习与交流。项目并非《崩坏3》官方产品；图像素材的使用及再分发注意事项见 `android/ASSET_NOTICE.md`。
