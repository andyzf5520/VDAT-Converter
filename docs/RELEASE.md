# 发布说明

## v1.0.1

发布目录：

```text
release/v1.0.1/
```

发布包：

- `VDAT-Converter-v1.0.1-windows-x64.zip`：Windows 单文件 EXE，内置 FFmpeg。
- `VDAT-Converter-v1.0.1-android-apk.zip`：Android APK。

## 发布流程

1. 构建 Windows EXE 和 Android APK。
2. 清理旧的临时安装包。
3. 将最终安装包复制到 `release/v1.0.1/`。
4. 分别压缩 Windows 和 Android 安装包。
5. 提交代码，打 `v1.0.1` 标签。
6. 推送代码和标签到 GitHub。
7. 创建 GitHub Release，并上传两个压缩包。
