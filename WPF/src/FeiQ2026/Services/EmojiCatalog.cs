using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace FeiQ2026.Services;

/// <summary>
/// 表情映射表：Unicode ↔ codepoint 文件名 ↔ 短名。
/// 素材放 Assets/Emoji/ 下，文件名用 codepoint（如 1f600.png）。
/// </summary>
public static class EmojiCatalog
{
    public sealed record Entry(string Unicode, string Codepoint, string ShortName);

    /// <summary>预设 40 个（与面板一致）</summary>
    public static readonly Entry[] Preset =
    {
        new("😀", "1f600", "grinning"),
        new("😁", "1f601", "grin"),
        new("😂", "1f602", "joy"),
        new("🤣", "1f923", "rofl"),
        new("😊", "1f60a", "blush"),
        new("😍", "1f60d", "heart_eyes"),
        new("😘", "1f618", "kissing_heart"),
        new("😜", "1f61c", "stuck_out_tongue_winking"),
        new("🤔", "1f914", "thinking"),
        new("😎", "1f60e", "sunglasses"),
        new("😢", "1f622", "cry"),
        new("😭", "1f62d", "sob"),
        new("😡", "1f621", "rage"),
        new("👍", "1f44d", "thumbsup"),
        new("👎", "1f44e", "thumbsdown"),
        new("👏", "1f44f", "clap"),
        new("🙏", "1f64f", "pray"),
        new("❤️", "2764", "heart"),          // 实际文件常是 2764.png 或 2764-fe0f.png
        new("💔", "1f494", "broken_heart"),
        new("🔥", "1f525", "fire"),
        new("🎉", "1f389", "tada"),
        new("✨", "2728", "sparkles"),
        new("💯", "1f4af", "100"),
        new("✅", "2705", "white_check_mark"),
        new("❌", "274c", "x"),
        new("⭐", "2b50", "star"),
        new("🌟", "1f31f", "star2"),
        new("💡", "1f4a1", "bulb"),
        new("📌", "1f4cc", "pushpin"),
        new("📎", "1f4ce", "paperclip"),
        new("📷", "1f4f7", "camera"),
        new("🎵", "1f3b5", "musical_note"),
        new("🎬", "1f3ac", "clapper"),
        new("📁", "1f4c1", "file_folder"),
        new("💻", "1f4bb", "computer"),
        new("📱", "1f4f1", "iphone"),
        new("☕", "2615", "coffee"),
        new("🍺", "1f37a", "beer"),
        new("🍕", "1f355", "pizza"),
        new("🎁", "1f381", "gift"),
    };

    private static string? _dir;
    private static readonly Dictionary<string, ImageSource> _cache = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>素材目录：优先 exe 旁 Assets/Emoji，其次开发时源码相对路径</summary>
    public static string ResolveDir()
    {
        if (_dir != null) return _dir;

        var candidates = new[]
        {
            Path.Combine(AppContext.BaseDirectory, "Assets", "Emoji"),
            Path.Combine(AppContext.BaseDirectory, "Emoji"),
            // 开发时：从 bin 回到项目
            Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "Assets", "Emoji")),
        };
        foreach (var d in candidates)
        {
            if (Directory.Exists(d))
            {
                _dir = d;
                return d;
            }
        }
        _dir = candidates[0]; // 默认，即使不存在
        return _dir;
    }

    /// <summary>按 codepoint 找图片（支持 1f600.png / 2764.png / 2764-fe0f.png）</summary>
    public static ImageSource? GetImage(string codepoint)
    {
        if (string.IsNullOrEmpty(codepoint)) return null;
        if (_cache.TryGetValue(codepoint, out var cached)) return cached;

        var dir = ResolveDir();
        if (!Directory.Exists(dir)) return null;

        var tries = new[]
        {
            codepoint + ".png",
            codepoint + ".jpg",
            codepoint + ".webp",
            codepoint + "-fe0f.png",
            "emoji_u" + codepoint.Replace("-", "_") + ".png",
        };

        foreach (var name in tries)
        {
            var path = Path.Combine(dir, name);
            if (!File.Exists(path)) continue;
            try
            {
                var bmp = new BitmapImage();
                bmp.BeginInit();
                bmp.CacheOption = BitmapCacheOption.OnLoad;
                bmp.DecodePixelWidth = 64;
                bmp.UriSource = new Uri(path, UriKind.Absolute);
                bmp.EndInit();
                bmp.Freeze();
                _cache[codepoint] = bmp;
                return bmp;
            }
            catch { /* next */ }
        }
        return null;
    }

    public static ImageSource? GetImage(Entry e) => GetImage(e.Codepoint);

    /// <summary>短名 → Entry（用于 [emoji:smile] 这种扩展，可选）</summary>
    public static Entry? FindByShortName(string shortName)
        => Preset.FirstOrDefault(x => x.ShortName.Equals(shortName, StringComparison.OrdinalIgnoreCase));

    public static Entry? FindByUnicode(string unicode)
        => Preset.FirstOrDefault(x => x.Unicode == unicode);
}
