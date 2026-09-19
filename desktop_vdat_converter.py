"""VDAT 目录转 MP4（Windows 图形版）

需要：Python 3.10+；发布版已内置 ffmpeg，源码运行时可把 ffmpeg.exe 放在本文件同目录或加入 PATH。
仅处理本地已有密钥的 .vdat_contents 目录。
"""

from __future__ import annotations

import os
import shutil
import sys
import subprocess
import tempfile
import threading
from pathlib import Path
from tkinter import END, LEFT, RIGHT, BOTH, filedialog, messagebox, StringVar, Tk, Listbox, Button, Label, Frame, Scrollbar, VERTICAL
try:
    from Crypto.Cipher import AES as _NativeAES
except ImportError:
    _NativeAES = None


_SBOX = [
    0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
    0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
    0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
    0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
    0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
    0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
    0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
    0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
    0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
    0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
    0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
    0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
    0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
    0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
    0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
    0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16,
]
_INV_SBOX = [0] * 256
for _i, _v in enumerate(_SBOX): _INV_SBOX[_v] = _i
_RCON = [0, 1, 2, 4, 8, 16, 32, 64, 128, 27, 54]


def _aes_round_keys(key: bytes) -> list[bytes]:
    expanded = bytearray(key)
    for i in range(4, 44):
        word = list(expanded[(i - 1) * 4:i * 4])
        if i % 4 == 0:
            word = [_SBOX[x] for x in word[1:] + word[:1]]
            word[0] ^= _RCON[i // 4]
        for j in range(4): expanded.append(expanded[(i - 4) * 4 + j] ^ word[j])
    return [bytes(expanded[i:i + 16]) for i in range(0, 176, 16)]


def _mul(a: int, b: int) -> int:
    result = 0
    for _ in range(8):
        if b & 1: result ^= a
        a = ((a << 1) ^ (0x11B if a & 0x80 else 0)) & 0xFF
        b >>= 1
    return result


def _aes_decrypt_block(block: bytes, round_keys: list[bytes]) -> bytes:
    state = [list(block[i:i + 4]) for i in range(0, 16, 4)]
    def add_key(key):
        for c in range(4):
            for r in range(4): state[c][r] ^= key[c * 4 + r]
    def inv_shift_rows():
        for r in range(1, 4):
            row = [state[c][r] for c in range(4)]
            for c in range(4): state[c][r] = row[(c - r) % 4]
    def inv_sub_bytes():
        for c in range(4):
            for r in range(4): state[c][r] = _INV_SBOX[state[c][r]]
    def inv_mix_columns():
        for c in range(4):
            a = state[c]
            state[c] = [_mul(a[0],14)^_mul(a[1],11)^_mul(a[2],13)^_mul(a[3],9),
                        _mul(a[0],9)^_mul(a[1],14)^_mul(a[2],11)^_mul(a[3],13),
                        _mul(a[0],13)^_mul(a[1],9)^_mul(a[2],14)^_mul(a[3],11),
                        _mul(a[0],11)^_mul(a[1],13)^_mul(a[2],9)^_mul(a[3],14)]
    add_key(round_keys[10])
    for rnd in range(9, 0, -1):
        inv_shift_rows(); inv_sub_bytes(); add_key(round_keys[rnd]); inv_mix_columns()
    inv_shift_rows(); inv_sub_bytes(); add_key(round_keys[0])
    return bytes(x for col in state for x in col)


def _aes_cbc_decrypt(data: bytes, key: bytes) -> bytes:
    if len(data) % 16: raise ValueError("加密分片长度不是 16 的倍数")
    if _NativeAES is not None:
        return _NativeAES.new(key, _NativeAES.MODE_CBC, bytes(16)).decrypt(data)
    keys = _aes_round_keys(key)
    previous = bytes(16)
    plain = bytearray()
    for offset in range(0, len(data), 16):
        current = data[offset:offset + 16]
        decoded = _aes_decrypt_block(current, keys)
        plain.extend(a ^ b for a, b in zip(decoded, previous))
        previous = current
    return bytes(plain)


def is_vdat_dir(path: Path) -> bool:
    if not path.is_dir() or not (path / "0.key").is_file():
        return False
    return any(p.is_file() and p.name.isdecimal() for p in path.iterdir())


def find_vdat_dirs(root: Path) -> list[Path]:
    if is_vdat_dir(root):
        return [root]
    return sorted((p for p in root.rglob("*") if is_vdat_dir(p)), key=lambda p: str(p).lower())


def bundled_path(name: str) -> Path:
    if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS"):
        return Path(sys._MEIPASS) / name
    if getattr(sys, "frozen", False):
        return Path(sys.executable).parent / name
    return Path(__file__).parent / name


def find_ffmpeg() -> str:
    bundled = bundled_path("ffmpeg.exe")
    if bundled.is_file():
        return str(bundled)
    beside_exe = Path(sys.executable).parent / "ffmpeg.exe"
    if beside_exe.is_file():
        return str(beside_exe)
    from_path = shutil.which("ffmpeg")
    if from_path:
        return from_path
    raise RuntimeError("找不到 ffmpeg。发布版应已内置；源码运行时请将 ffmpeg.exe 放到工具旁边，或加入系统 PATH。")


def decrypt_directory(source: Path, output: Path, report) -> None:
    ffmpeg = find_ffmpeg()
    key = (source / "0.key").read_bytes()
    if len(key) != 16:
        raise RuntimeError("0.key 不是有效的 AES-128 密钥。")
    chunks = sorted((p for p in source.iterdir() if p.is_file() and p.name.isdecimal()), key=lambda p: int(p.name))
    if not chunks:
        raise RuntimeError("目录中没有数字视频分片。")

    with tempfile.TemporaryDirectory(prefix="vdat-") as temp_name:
        temp_ts = Path(temp_name) / "video.ts"
        with temp_ts.open("wb") as output_stream:
            for index, chunk in enumerate(chunks, 1):
                report(f"{source.name}: 解密 {index}/{len(chunks)}")
                if chunk.stat().st_size % 16:
                    raise RuntimeError(f"分片 {chunk.name} 长度异常。")
                # 每个 HLS 分片独立使用 IV=0 的 AES-CBC，无填充。
                output_stream.write(_aes_cbc_decrypt(chunk.read_bytes(), key))

        output.parent.mkdir(parents=True, exist_ok=True)
        report(f"{source.name}: 封装 MP4")
        command = [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", str(temp_ts), "-map", "0", "-c", "copy", "-movflags", "+faststart", str(output)]
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        if result.returncode:
            raise RuntimeError(result.stderr.decode(errors="replace").strip() or "FFmpeg 转换失败")


class App:
    def __init__(self, root: Tk):
        self.root = root
        self.root.title("VDAT Converter")
        self.root.geometry("720x460")
        self.root.minsize(620, 380)
        self.root_dir = StringVar(value="")
        self.output_dir = StringVar(value=str(Path.home() / "Videos" / "VdatConverted"))
        self.status = StringVar(value="选择一个 .vdat_contents 目录，或选择包含多个视频目录的上级目录。")
        self.items: list[Path] = []
        self.last_output_dir: Path | None = None

        Label(root, text="VDAT 转 MP4", font=("Segoe UI", 20, "bold")).pack(anchor="w", padx=18, pady=(16, 4))
        Label(root, text="自动读取 0.key，解密数字分片并保留原文件。", fg="#555").pack(anchor="w", padx=18, pady=(0, 12))
        top = Frame(root); top.pack(fill="x", padx=18)
        Button(top, text="选择输入目录", command=self.choose_input).pack(side=LEFT)
        Label(top, textvariable=self.root_dir, anchor="w").pack(side=LEFT, fill="x", expand=True, padx=10)
        Button(root, text="选择输出目录", command=self.choose_output).pack(anchor="w", padx=18, pady=(8, 2))
        Label(root, textvariable=self.output_dir, anchor="w").pack(fill="x", padx=18)

        list_frame = Frame(root); list_frame.pack(fill=BOTH, expand=True, padx=18, pady=12)
        scroll = Scrollbar(list_frame, orient=VERTICAL); scroll.pack(side=RIGHT, fill="y")
        self.listbox = Listbox(list_frame, yscrollcommand=scroll.set, selectmode="extended")
        self.listbox.pack(side=LEFT, fill=BOTH, expand=True); scroll.config(command=self.listbox.yview)
        bottom = Frame(root); bottom.pack(fill="x", padx=18, pady=(0, 16))
        Button(bottom, text="开始转换", command=self.start, width=14).pack(side=RIGHT)
        Button(bottom, text="打开输出目录", command=self.open_output_dir, width=14).pack(side=RIGHT, padx=(0, 8))
        Label(bottom, textvariable=self.status, anchor="w").pack(side=LEFT, fill="x", expand=True)

    def choose_input(self):
        selected = filedialog.askdirectory(title="选择 .vdat_contents 或其上级目录")
        if not selected: return
        self.root_dir.set(selected)
        self.items = find_vdat_dirs(Path(selected))
        self.listbox.delete(0, END)
        for item in self.items: self.listbox.insert(END, str(item))
        self.status.set(f"找到 {len(self.items)} 个可转换目录。")

    def choose_output(self):
        selected = filedialog.askdirectory(title="选择 MP4 输出目录")
        if selected: self.output_dir.set(selected)

    def start(self):
        if not self.items:
            messagebox.showwarning("提示", "请先选择并扫描输入目录。"); return
        threading.Thread(target=self.run, daemon=True).start()

    def open_output_dir(self):
        target = self.last_output_dir or Path(self.output_dir.get())
        target.mkdir(parents=True, exist_ok=True)
        os.startfile(target)

    def run(self):
        success = 0
        output_dir = Path(self.output_dir.get())
        for item in self.items:
            output = output_dir / (item.name.removesuffix(".vdat_contents") + ".mp4")
            try:
                decrypt_directory(item, output, lambda text: self.root.after(0, self.status.set, text))
                self.last_output_dir = output.parent
                success += 1
            except Exception as exc:
                self.root.after(0, self.status.set, f"失败：{item.name} - {exc}")
                continue
        self.root.after(0, self.status.set, f"完成：{success}/{len(self.items)} 个视频已输出。")
        self.root.after(0, lambda: self.finish_message(success))

    def finish_message(self, success: int):
        if messagebox.askyesno("转换完成", f"成功转换 {success}/{len(self.items)} 个视频。\n是否打开输出目录？"):
            self.open_output_dir()


if __name__ == "__main__":
    app_root = Tk()
    App(app_root)
    app_root.mainloop()
