# VDAT Converter 中文说明

VDAT Converter 是一个 Windows 和 Android 工具，用于把本地已下载、且用户拥有或获授权的视频转换为 MP4。

## 主要功能

- 支持 VDAT 视频目录：自动识别目录内的 `0.key` 和数字分片。
- 支持完整 `.vdat` 视频文件：如果 `.vdat` 本身是 MP4，程序会直接另存为 `.mp4`。
- Android 端支持自定义保存目录和输出文件名。
- Android 转换完成后可以“查看视频”或“打开所在目录”。
- Windows 发布版内置 FFmpeg，解压后只有一个 EXE，直接运行即可。

## 下载与安装

正式安装包位于：

```text
release/v1.0.1/
```

文件说明：

- `VDAT-Converter-v1.0.1-windows-x64.zip`：Windows 版，内含单文件 `VDAT-Converter.exe`。
- `VDAT-Converter-v1.0.1-android-apk.zip`：Android 版，内含 `VDAT-Converter-Android.apk`。

## Android 使用方法

1. 安装 `VDAT-Converter-Android.apk`。
2. 在“步骤 1 · 保存位置”选择 MP4 输出目录，默认保存到 `Download/VdatConverter`。
3. 在“步骤 2 · 视频来源类型”选择“VDAT 视频目录”或“.vdat 视频文件”。
4. 在“步骤 3”选择对应的视频目录或 `.vdat` 文件。
5. 输出文件名会自动使用来源名称，也可以手动修改。
6. 点击“开始转换”。
7. 转换完成后，可选择“查看视频”或“打开所在目录”。

说明：几 KB 的 `.vdat` 通常是播放列表或元数据，不是完整视频。遇到这种文件时，请选择同名的视频目录。

## Windows 使用方法

1. 解压 `VDAT-Converter-v1.0.1-windows-x64.zip`。
2. 运行 `VDAT-Converter.exe`。
3. 选择 `.vdat_contents` 目录，或选择包含多个 VDAT 视频目录的上级目录。
4. 选择输出目录。
5. 点击“开始转换”。
6. 完成后可直接打开输出目录。

Windows 版已把 `ffmpeg.exe` 打包进 EXE，不需要额外下载 FFmpeg。

## 项目结构

- `app/`：Android 客户端源码。
- `docs/`：使用、构建、发布和版本文档。
- `release/`：最终安装包。
- `desktop_vdat_converter.py`：Windows 桌面端源码。
- `VDAT-Converter.spec`：Windows 单文件 EXE 打包配置。
- `dist/`：本地构建输出，不作为正式发布目录。

## 使用限制

本项目只处理本地已有密钥和视频数据，不用于绕过账号、权限或版权保护。请仅转换自己拥有或获授权的视频。
