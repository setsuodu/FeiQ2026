using System.IO;
using System.Text.Json;

namespace MiniFeiQ.Services;

/// <summary>本地配置（用户名、头像、目录等），存 JSON。</summary>
public sealed class AppSettings
{
    private static readonly string ConfigDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "FeiQ2026");
    private static readonly string ConfigPath = Path.Combine(ConfigDir, "settings.json");

    public string UserName { get; set; } = Environment.UserName;
    public string? AvatarPath { get; set; }
    public string DownloadDir { get; set; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "FeiQ2026", "Downloads");
    public string ChatDbDir { get; set; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "FeiQ2026");
    public string LastServerUrl { get; set; } = "wss://s0.v100.vip:27658/ws";
    public int LastModeIndex { get; set; } // 0=UDP 1=WS

    public string ChatDbPath => Path.Combine(ChatDbDir, "chat.db");

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(ConfigPath))
            {
                var json = File.ReadAllText(ConfigPath);
                var s = JsonSerializer.Deserialize<AppSettings>(json);
                if (s != null)
                {
                    s.EnsureDirs();
                    return s;
                }
            }
        }
        catch { /* use defaults */ }

        var def = new AppSettings();
        def.EnsureDirs();
        return def;
    }

    public void Save()
    {
        Directory.CreateDirectory(ConfigDir);
        EnsureDirs();
        var json = JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(ConfigPath, json);
    }

    public void EnsureDirs()
    {
        try { Directory.CreateDirectory(DownloadDir); } catch { }
        try { Directory.CreateDirectory(ChatDbDir); } catch { }
    }
}
