using System.Security.Cryptography;

var source = Directory.GetDirectories(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Desktop)), "*.vdat_contents").Single();
var output = Path.GetFullPath(Path.Combine("..", "test-results", "native-sample.mp4"));
Directory.CreateDirectory(Path.GetDirectoryName(output)!);
var temp = Path.Combine(Path.GetTempPath(), "vdat-native-test.ts");
var key = File.ReadAllBytes(Path.Combine(source, "0.key"));
var chunks = Directory.GetFiles(source).Where(x => int.TryParse(Path.GetFileName(x), out _)).OrderBy(x => int.Parse(Path.GetFileName(x))).ToArray();
using (var writer = File.Create(temp))
foreach (var chunk in chunks)
{
    using var aes = Aes.Create();
    aes.Key = key; aes.IV = new byte[16]; aes.Mode = CipherMode.CBC; aes.Padding = PaddingMode.None;
    using var crypto = new CryptoStream(writer, aes.CreateDecryptor(), CryptoStreamMode.Write, leaveOpen: true);
    var bytes = File.ReadAllBytes(chunk);
    crypto.Write(bytes, 0, bytes.Length);
    crypto.FlushFinalBlock();
}
var psi = new System.Diagnostics.ProcessStartInfo(Path.Combine("dist", "ffmpeg.exe"), $"-y -hide_banner -loglevel error -i \"{temp}\" -map 0 -c copy -movflags +faststart \"{output}\"") { UseShellExecute = false, RedirectStandardError = true };
using var ffmpeg = System.Diagnostics.Process.Start(psi)!;
var error = ffmpeg.StandardError.ReadToEnd(); ffmpeg.WaitForExit();
File.Delete(temp);
if (ffmpeg.ExitCode != 0) throw new Exception(error);
Console.WriteLine($"chunks={chunks.Length}; output={output}; bytes={new FileInfo(output).Length}");
