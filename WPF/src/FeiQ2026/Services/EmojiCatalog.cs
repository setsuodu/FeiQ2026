using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace FeiQ2026.Services;

/// <summary>
/// 表情映射表（从 Assets/Emoji/emoji_map.json 加载）。
/// 支持 char / code / image / shortcode，气泡渲染时用图片替换 Unicode。
/// </summary>
public static class EmojiCatalog
{
    public sealed class EmojiItem
    {
        [JsonPropertyName("char")] public string Char { get; set; } = "";
        [JsonPropertyName("code")] public string Code { get; set; } = "";
        [JsonPropertyName("image")] public string Image { get; set; } = "";
        [JsonPropertyName("shortcode")] public string Shortcode { get; set; } = "";
    }

    public sealed class Category
    {
        [JsonPropertyName("category_name")] public string CategoryName { get; set; } = "";
        [JsonPropertyName("category_title")] public string CategoryTitle { get; set; } = "";
        [JsonPropertyName("emojis")] public List<EmojiItem> Emojis { get; set; } = new();
    }

    private static List<Category>? _categories;
    private static Dictionary<string, EmojiItem>? _byChar;
    private static Dictionary<string, EmojiItem>? _byShortcode;
    private static readonly Dictionary<string, ImageSource> _imgCache = new(StringComparer.OrdinalIgnoreCase);
    private static string? _dir;

    public static IReadOnlyList<Category> Categories
    {
        get { EnsureLoaded(); return _categories!; }
    }

    public static IReadOnlyDictionary<string, EmojiItem> ByChar
    {
        get { EnsureLoaded(); return _byChar!; }
    }

    private static void EnsureLoaded()
    {
        if (_categories != null) return;

        _categories = new List<Category>();
        _byChar = new Dictionary<string, EmojiItem>();
        _byShortcode = new Dictionary<string, EmojiItem>(StringComparer.OrdinalIgnoreCase);

        var dir = ResolveDir();
        var jsonPath = Path.Combine(dir, "emoji_map.json");

        if (File.Exists(jsonPath))
        {
            try
            {
                var json = File.ReadAllText(jsonPath);
                var list = JsonSerializer.Deserialize<List<Category>>(json);
                if (list != null && list.Count > 0)
                {
                    _categories = list;
                    foreach (var cat in list)
                    foreach (var e in cat.Emojis)
                    {
                        if (!string.IsNullOrEmpty(e.Char))
                            _byChar[e.Char] = e;
                        // 也索引去 FE0F 后的版本
                        var stripped = e.Char.Replace("\uFE0F", "");
                        if (stripped != e.Char && !_byChar.ContainsKey(stripped))
                            _byChar[stripped] = e;
                        if (!string.IsNullOrEmpty(e.Shortcode))
                            _byShortcode[e.Shortcode] = e;
                    }
                    return;
                }
            }
            catch { /* fall through to builtin */ }
        }

        // 无 json 时用内置 fallback
        LoadBuiltin();
    }

    private static void LoadBuiltin()
    {
        var face = new Category
        {
            CategoryName = "face",
            CategoryTitle = "表情与人物",
            Emojis = new List<EmojiItem>
            {
                new() { Char = "😀", Code = "1f600", Image = "1f600.png", Shortcode = "[:smile:]" },
                new() { Char = "😁", Code = "1f601", Image = "1f601.png", Shortcode = "[:grin:]" },
                new() { Char = "😂", Code = "1f602", Image = "1f602.png", Shortcode = "[:joy:]" },
                new() { Char = "🤣", Code = "1f923", Image = "1f923.png", Shortcode = "[:rofl:]" },
                new() { Char = "😊", Code = "1f60a", Image = "1f60a.png", Shortcode = "[:blush:]" },
                new() { Char = "😍", Code = "1f60d", Image = "1f60d.png", Shortcode = "[:heart_eyes:]" },
                new() { Char = "😘", Code = "1f618", Image = "1f618.png", Shortcode = "[:kissing_heart:]" },
                new() { Char = "😜", Code = "1f61c", Image = "1f61c.png", Shortcode = "[:stuck_out_tongue:]" },
                new() { Char = "🤔", Code = "1f914", Image = "1f914.png", Shortcode = "[:thinking:]" },
                new() { Char = "😎", Code = "1f60e", Image = "1f60e.png", Shortcode = "[:sunglasses:]" },
                new() { Char = "😢", Code = "1f622", Image = "1f622.png", Shortcode = "[:cry:]" },
                new() { Char = "😭", Code = "1f62d", Image = "1f62d.png", Shortcode = "[:sob:]" },
                new() { Char = "😡", Code = "1f621", Image = "1f621.png", Shortcode = "[:rage:]" },
                new() { Char = "👍", Code = "1f44d", Image = "1f44d.png", Shortcode = "[:like:]" },
                new() { Char = "👎", Code = "1f44e", Image = "1f44e.png", Shortcode = "[:dislike:]" },
                new() { Char = "👏", Code = "1f44f", Image = "1f44f.png", Shortcode = "[:clap:]" },
                new() { Char = "🙏", Code = "1f64f", Image = "1f64f.png", Shortcode = "[:pray:]" },
                new() { Char = "❤️", Code = "2764", Image = "2764.png", Shortcode = "[:heart:]" },
                new() { Char = "💔", Code = "1f494", Image = "1f494.png", Shortcode = "[:broken_heart:]" },
                new() { Char = "🔥", Code = "1f525", Image = "1f525.png", Shortcode = "[:fire:]" },
                new() { Char = "🎉", Code = "1f389", Image = "1f389.png", Shortcode = "[:tada:]" },
                new() { Char = "✨", Code = "2728", Image = "2728.png", Shortcode = "[:sparkles:]" },
                new() { Char = "💯", Code = "1f4af", Image = "1f4af.png", Shortcode = "[:100:]" },
                new() { Char = "✅", Code = "2705", Image = "2705.png", Shortcode = "[:check:]" },
                new() { Char = "❌", Code = "274c", Image = "274c.png", Shortcode = "[:x:]" },
                new() { Char = "⭐", Code = "2b50", Image = "2b50.png", Shortcode = "[:star:]" },
                new() { Char = "🌟", Code = "1f31f", Image = "1f31f.png", Shortcode = "[:star2:]" },
                new() { Char = "💡", Code = "1f4a1", Image = "1f4a1.png", Shortcode = "[:bulb:]" },
                new() { Char = "📌", Code = "1f4cc", Image = "1f4cc.png", Shortcode = "[:pushpin:]" },
                new() { Char = "📎", Code = "1f4ce", Image = "1f4ce.png", Shortcode = "[:paperclip:]" },
                new() { Char = "📷", Code = "1f4f7", Image = "1f4f7.png", Shortcode = "[:camera:]" },
                new() { Char = "🎵", Code = "1f3b5", Image = "1f3b5.png", Shortcode = "[:musical_note:]" },
                new() { Char = "🎬", Code = "1f3ac", Image = "1f3ac.png", Shortcode = "[:clapper:]" },
                new() { Char = "📁", Code = "1f4c1", Image = "1f4c1.png", Shortcode = "[:folder:]" },
                new() { Char = "💻", Code = "1f4bb", Image = "1f4bb.png", Shortcode = "[:computer:]" },
                new() { Char = "📱", Code = "1f4f1", Image = "1f4f1.png", Shortcode = "[:phone:]" },
                new() { Char = "☕", Code = "2615", Image = "2615.png", Shortcode = "[:coffee:]" },
                new() { Char = "🍺", Code = "1f37a", Image = "1f37a.png", Shortcode = "[:beer:]" },
                new() { Char = "🍕", Code = "1f355", Image = "1f355.png", Shortcode = "[:pizza:]" },
                new() { Char = "🎁", Code = "1f381", Image = "1f381.png", Shortcode = "[:gift:]" },
            }
        };
        _categories = new List<Category> { face };
        foreach (var e in face.Emojis)
        {
            _byChar![e.Char] = e;
            var stripped = e.Char.Replace("\uFE0F", "");
            if (stripped != e.Char) _byChar[stripped] = e;
            _byShortcode![e.Shortcode] = e;
        }
    }

    public static string ResolveDir()
    {
        if (_dir != null) return _dir;
        var candidates = new[]
        {
            Path.Combine(AppContext.BaseDirectory, "Assets", "Emoji"),
            Path.Combine(AppContext.BaseDirectory, "Emoji"),
            Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "Assets", "Emoji")),
        };
        foreach (var d in candidates)
            if (Directory.Exists(d)) { _dir = d; return d; }
        _dir = candidates[0];
        return _dir;
    }

    public static ImageSource? GetImage(EmojiItem item)
    {
        if (item == null) return null;
        var key = item.Image ?? item.Code;
        if (string.IsNullOrEmpty(key)) return null;
        if (_imgCache.TryGetValue(key, out var cached)) return cached;

        var dir = ResolveDir();
        var tries = new List<string>();
        if (!string.IsNullOrEmpty(item.Image))
            tries.Add(item.Image);
        if (!string.IsNullOrEmpty(item.Code))
        {
            tries.Add(item.Code + ".png");
            tries.Add(item.Code + "-fe0f.png");
            tries.Add("emoji_u" + item.Code.Replace("-", "_") + ".png");
        }

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
                _imgCache[key] = bmp;
                return bmp;
            }
            catch { }
        }
        return null;
    }

    public static ImageSource? GetImageByChar(string ch)
    {
        EnsureLoaded();
        if (_byChar!.TryGetValue(ch, out var item))
            return GetImage(item);
        // 尝试去掉 FE0F
        var stripped = ch.Replace("\uFE0F", "");
        if (stripped != ch && _byChar.TryGetValue(stripped, out item))
            return GetImage(item);
        return null;
    }

    /// <summary>
    /// 把文本拆成「普通文字 / 表情图片」片段，用于气泡富文本渲染。
    /// </summary>
    public static List<(bool IsEmoji, string Text, ImageSource? Img)> ParseSegments(string? text)
    {
        var result = new List<(bool, string, ImageSource?)>();
        if (string.IsNullOrEmpty(text)) return result;

        EnsureLoaded();
        var sb = new System.Text.StringBuilder();
        var enumr = System.Globalization.StringInfo.GetTextElementEnumerator(text);
        while (enumr.MoveNext())
        {
            var elem = enumr.GetTextElement();
            var img = GetImageByChar(elem);
            if (img != null)
            {
                if (sb.Length > 0)
                {
                    result.Add((false, sb.ToString(), null));
                    sb.Clear();
                }
                result.Add((true, elem, img));
            }
            else
            {
                sb.Append(elem);
            }
        }
        if (sb.Length > 0)
            result.Add((false, sb.ToString(), null));
        return result;
    }
}
