# Changelog

## v1.0.1 - 2026-09-19

### 新增 / Added

- Android 界面显示版本号 `v1.0.1`。
- Android 转换完成后新增两个动作：“查看视频”和“打开所在目录”。
- Android 来源选择拆分为“视频来源类型”和“选择目录/文件”两个步骤。
- Android 右上角新增菜单，包含设置、关于软件、开源项目地址和版本信息。
- 设置页新增默认保存目录、转换完成后动作、文件名规则、主题外观和调试日志选项。
- Windows 发布版将 FFmpeg 打包进单个 EXE。
- 新增中英文 README 和版本变更说明。

### 优化 / Changed

- 缩小 Android 顶部标题，界面步骤更清晰。
- 发布包统一放入 `release/v1.0.1/`。
- 文档目录规范化，使用 `docs/` 管理详细说明。
- 移除重复的 PyInstaller spec 配置，保留 `VDAT-Converter.spec`。
- 构建产物不再散落在根目录。

### 修复 / Fixed

- Android 完整分片目录转换不再只输出第一小段。
- 修复 Android FFmpegKit 运行时缺少 `smart-exception` 依赖导致的转换报错。
- 重新选择视频来源后，输出文件名会自动更新为新来源名称。

## v1.0.0 - 2026-09-19

### Added

- 初始开源版本。
- Android 支持 VDAT 视频目录和完整 `.vdat` 文件转换。
- Windows 桌面版支持 VDAT 分片目录转 MP4。
- 增加基础使用说明和构建说明。
