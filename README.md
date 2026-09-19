# VDAT Converter

VDAT Converter 是一个 Windows 和 Android 工具，用于把已下载到本地、且用户拥有或获授权的视频转换为 MP4。

## 支持的来源

### VDAT 视频目录

选择夸克下载目录中的视频目录即可。程序会自动识别目录内的 `0.key` 和数字分片，不要求用户手动进入或填写 `.vdat_contents`。

### `.vdat` 视频文件

部分 `.vdat` 文件本身就是 MP4，只是扩展名仍为 `.vdat`，程序会识别 MP4 文件头并直接保存为 MP4。

几 KB 的 `.vdat` 通常是播放列表或元数据，实际视频仍在同名 VDAT 视频目录中。遇到这种文件时，请改选对应的视频目录。

## Android 使用方法

1. 安装 `VDAT-Converter-Android.apk`。
2. 在“保存目录”中选择输出位置，默认是 `Download/VdatConverter`。
3. 在“来源类型”中选择“VDAT 视频目录”或“.vdat 视频文件”。
4. 选择来源后，输出文件名会自动填充，也可以自行修改。
5. 点击“开始转换”，完成后 MP4 会保存到指定目录。

Android 端使用 FFmpeg 合并完整分片，避免只生成第一个分片的短视频。

## Windows 使用方法

发布包位于 `dist`：

- `VDAT-Converter.exe`
- `ffmpeg.exe`

两个文件需要放在同一个目录。运行 EXE 后选择 VDAT 视频目录，设置输出目录即可。

## 从源码构建

### Windows

```powershell
python -m pip install pycryptodome pyinstaller
python -m PyInstaller --clean --noconfirm VDAT-Converter.spec
```

桌面版需要 FFmpeg 运行文件。请把对应的 `ffmpeg.exe` 放在生成的 EXE 旁边。

### Android

```powershell
gradle.bat assembleDebug --no-daemon
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。

## 项目结构

- `app/`：Android 客户端
- `desktop_vdat_converter.py`：Windows 桌面端源码
- `VDAT-Converter.spec`：Windows 打包配置
- `dist/`：桌面发布文件

## 说明

本项目只处理本地已有密钥和视频数据，不用于绕过账号、权限或版权保护。请仅转换自己拥有或获授权的视频。
