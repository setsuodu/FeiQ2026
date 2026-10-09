using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace FeiQ2026.Services;

/// <summary>
/// FeiQ2026 之间同步的头像缓存（与飞秋2013无关）。
/// 协议：普通文本消息，正文以 Magic 前缀开头 + Base64 JPEG 缩略图。
/// 请求协议：对方没有本地缓存时发 MagicReq，对端回推自己的头像。
/// </summary>
public static class AvatarCache
{
    public const string Magic = "__MFQ_AVATAR__:";
    public const string MagicReq = "__MFQ_AVATAR_REQ__";

    private static readonly string CacheDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "FeiQ2026", "avatars");

    static AvatarCache()
    {
        Directory.CreateDirectory(CacheDir);
    }

    public static string PathFor(string peerKey)
    {
        var safe = string.Join("_", peerKey.Split(Path.GetInvalidFileNameChars()));
        return Path.Combine(CacheDir, safe + ".jpg");
    }

    public static void Save(string peerKey, byte[] jpegBytes)
    {
        if (jpegBytes.Length == 0 || jpegBytes.Length > 200_000) return;
        var path = PathFor(peerKey);
        File.WriteAllBytes(path, jpegBytes);
    }

    public static string? GetPath(string peerKey)
    {
        var path = PathFor(peerKey);
        return File.Exists(path) ? path : null;
    }

    /// <summary>
    /// 从文件加载 ImageSource。始终走 MemoryStream，避免同路径覆盖后仍显示旧图。
    /// </summary>
    public static ImageSource? LoadImage(string peerKey)
    {
        var path = GetPath(peerKey);
        return LoadImageFromPath(path);
    }

    public static ImageSource? LoadImageFromPath(string? path)
    {
        if (string.IsNullOrEmpty(path) || !File.Exists(path)) return null;
        try
        {
            var bytes = File.ReadAllBytes(path);
            using var ms = new MemoryStream(bytes);
            var bmp = new BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = BitmapCacheOption.OnLoad;
            bmp.StreamSource = ms;
            bmp.DecodePixelWidth = 96;
            bmp.EndInit();
            bmp.Freeze();
            return bmp;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// 中心裁剪为正方形（取短边），再缩放到 maxEdge，输出 JPEG。
    /// 上传本地头像与同步缩略图共用此逻辑。
    /// </summary>
    public static byte[]? EncodeThumbnail(string? avatarPath, int maxEdge = 64, int quality = 55)
    {
        if (string.IsNullOrEmpty(avatarPath) || !File.Exists(avatarPath)) return null;
        try
        {
            BitmapSource src;
            using (var fs = File.OpenRead(avatarPath))
            {
                var decoder = BitmapDecoder.Create(fs, BitmapCreateOptions.None, BitmapCacheOption.OnLoad);
                src = decoder.Frames[0];
            }

            int w = src.PixelWidth;
            int h = src.PixelHeight;
            if (w <= 0 || h <= 0) return null;

            // 1:1 中心裁剪
            int side = Math.Min(w, h);
            int x = (w - side) / 2;
            int y = (h - side) / 2;
            var cropped = new CroppedBitmap(src, new System.Windows.Int32Rect(x, y, side, side));

            // 缩放到 maxEdge
            double scale = (double)maxEdge / side;
            var scaled = new TransformedBitmap(cropped, new ScaleTransform(scale, scale));

            var encoder = new JpegBitmapEncoder { QualityLevel = quality };
            encoder.Frames.Add(BitmapFrame.Create(scaled));
            using var ms = new MemoryStream();
            encoder.Save(ms);
            return ms.ToArray();
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// 把任意图片裁成 1:1 正方形 JPEG 写到 destPath（用于本地头像落盘）。
    /// </summary>
    public static bool SaveSquareJpeg(string sourcePath, string destPath, int maxEdge = 256, int quality = 85)
    {
        var bytes = EncodeThumbnail(sourcePath, maxEdge, quality);
        if (bytes == null || bytes.Length == 0) return false;
        var dir = Path.GetDirectoryName(destPath);
        if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
        File.WriteAllBytes(destPath, bytes);
        return true;
    }

    public static string? BuildSyncMessage(string? avatarPath)
    {
        var bytes = EncodeThumbnail(avatarPath);
        if (bytes == null || bytes.Length == 0) return null;
        return Magic + Convert.ToBase64String(bytes);
    }

    public static bool TryParse(string text, out byte[] jpeg)
    {
        jpeg = Array.Empty<byte>();
        if (string.IsNullOrEmpty(text) || !text.StartsWith(Magic, StringComparison.Ordinal))
            return false;
        try
        {
            jpeg = Convert.FromBase64String(text[Magic.Length..]);
            return jpeg.Length > 0;
        }
        catch
        {
            return false;
        }
    }

    public static bool IsRequest(string text)
        => string.Equals(text?.Trim(), MagicReq, StringComparison.Ordinal);
}
