# VDAT Converter English Guide

VDAT Converter is a Windows and Android tool for converting locally downloaded VDAT videos to MP4 files.

## Features

- Converts VDAT chunk directories that contain `0.key` and numeric media chunks.
- Supports complete `.vdat` files when the file itself is an MP4 with a different extension.
- Android supports a custom output folder and custom output file name.
- Android can open the converted video or its output folder after conversion.
- Windows release bundles FFmpeg into a single EXE.

## Downloads

Release packages are stored in:

```text
release/v1.0.1/
```

Package list:

- `VDAT-Converter-v1.0.1-windows-x64.zip`: Windows package with one `VDAT-Converter.exe`.
- `VDAT-Converter-v1.0.1-android-apk.zip`: Android package with `VDAT-Converter-Android.apk`.

## Android Usage

1. Install `VDAT-Converter-Android.apk`.
2. In “Step 1 · Save Location”, choose the MP4 output folder. The default is `Download/VdatConverter`.
3. In “Step 2 · Source Type”, choose “VDAT video directory” or “.vdat video file”.
4. In “Step 3”, choose the matching video directory or `.vdat` file.
5. The output file name is filled from the source name and can be edited.
6. Tap “Start Convert”.
7. After conversion, tap “View Video” or “Open Folder”.

Note: very small `.vdat` files are often playlists or metadata, not complete videos. In that case, select the matching video directory instead.

## Windows Usage

1. Extract `VDAT-Converter-v1.0.1-windows-x64.zip`.
2. Run `VDAT-Converter.exe`.
3. Select a `.vdat_contents` directory, or select a parent folder that contains multiple VDAT video directories.
4. Choose the output folder.
5. Click “Start Convert”.
6. When conversion finishes, open the output folder from the app.

The Windows EXE already includes FFmpeg. No separate `ffmpeg.exe` download is required.

## Project Layout

- `app/`: Android client source.
- `docs/`: usage, build, release, and changelog documents.
- `release/`: final install packages.
- `desktop_vdat_converter.py`: Windows desktop source.
- `VDAT-Converter.spec`: Windows single-file EXE packaging config.
- `dist/`: local build output, not the final release directory.

## Notice

This project only processes local video files and keys that you own or are authorized to use. It is not intended to bypass account access, permissions, or copyright protection.
