using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace FeiQ2026.Services;

/// <summary>
/// FeiQ2026 之间同步的头像缓存（与飞秋2013无关）。
/// 协议：普通文本消息，正文以 Magic 前缀开头 + Base64 JPEG 缩略图。
/// </summary>
public static class AvatarCache
{
    public const string Magic = "__MFQ_AVATAR__:";

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

    public static ImageSource? LoadImage(string peerKey)
    {
        var path = GetPath(peerKey);
        if (path == null) return null;
        try
        {
            var bmp = new BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = BitmapCacheOption.OnLoad;
            bmp.UriSource = new Uri(path, UriKind.Absolute);
            bmp.EndInit();
            bmp.Freeze();
            return bmp;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>把本机头像压成小 JPEG，用于同步。</summary>
    public static byte[]? EncodeThumbnail(string? avatarPath, int maxEdge = 64, int quality = 55)
    {
        if (string.IsNullOrEmpty(avatarPath) || !File.Exists(avatarPath)) return null;
        try
        {
            var src = new BitmapImage();
            src.BeginInit();
            src.CacheOption = BitmapCacheOption.OnLoad;
            src.UriSource = new Uri(avatarPath, UriKind.Absolute);
            src.DecodePixelWidth = maxEdge;
            src.EndInit();
            src.Freeze();

            var encoder = new JpegBitmapEncoder { QualityLevel = quality };
            encoder.Frames.Add(BitmapFrame.Create(src));
            using var ms = new MemoryStream();
            encoder.Save(ms);
            return ms.ToArray();
        }
        catch
        {
            return null;
        }
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
}
