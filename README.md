# VDAT Converter

VDAT Converter is a Windows and Android tool that converts locally downloaded VDAT videos to MP4.

VDAT Converter 是一个 Windows 和 Android 工具，用于把本地已下载的 VDAT 视频转换为 MP4。

## Documentation

- [中文说明](docs/README.zh-CN.md)
- [English Guide](docs/README.en-US.md)
- [版本变更 / Changelog](docs/CHANGELOG.md)
- [构建说明 / Build](docs/BUILD.md)
- [发布说明 / Release](docs/RELEASE.md)

## Release Packages

Install packages are stored in `release/v1.0.1/`:

- `VDAT-Converter-v1.0.1-windows-x64.zip`
- `VDAT-Converter-v1.0.1-android-apk.zip`

The same files are attached to the GitHub Release.

## Highlights

- Converts VDAT chunk directories with `0.key` and numeric media chunks.
- Supports complete `.vdat` files that are MP4 files with a different extension.
- Android supports custom save folder and output file name.
- Android can open the converted video or its output folder after conversion.
- Windows release is a single EXE with FFmpeg bundled inside.

## Notice

This project only processes local video files and keys that you own or are authorized to use. It is not intended to bypass account access, permissions, or copyright protection.
