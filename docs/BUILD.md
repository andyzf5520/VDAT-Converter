# 构建说明

## Windows

准备 Python 3.10+，并安装依赖：

```powershell
python -m pip install pycryptodome pyinstaller
```

构建单文件 EXE：

```powershell
python -m PyInstaller --clean --noconfirm VDAT-Converter.spec
```

`VDAT-Converter.spec` 会把本地 `dist/ffmpeg.exe` 打进 EXE。源码运行时如果没有打包，需要把 `ffmpeg.exe` 放在源码同目录，或加入系统 `PATH`。

输出文件：

```text
dist/VDAT-Converter.exe
```

## Android

使用项目内的 Gradle 配置构建：

```powershell
gradle.bat assembleDebug --no-daemon
```

输出文件：

```text
app/build/outputs/apk/debug/app-debug.apk
```

当前 APK 使用 FFmpegKit 合并完整分片，避免只生成第一个分片的短视频。
