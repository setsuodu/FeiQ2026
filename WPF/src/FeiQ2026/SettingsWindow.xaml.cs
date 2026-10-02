using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using FeiQ2026.Services;
using Forms = System.Windows.Forms;

namespace FeiQ2026;

public partial class SettingsWindow : Window
{
    private readonly AppSettings _settings;
    private string? _avatarPath;

    public SettingsWindow(AppSettings settings)
    {
        InitializeComponent();
        _settings = settings;
        _avatarPath = settings.AvatarPath;

        UserNameBox.Text = settings.UserName;
        ServerUrlBox.Text = settings.LastServerUrl;
        DownloadDirBox.Text = settings.DownloadDir;
        ChatDirBox.Text = settings.ChatDbDir;
        RefreshAvatarPreview();
    }

    private void RefreshAvatarPreview()
    {
        if (!string.IsNullOrEmpty(_avatarPath) && File.Exists(_avatarPath))
        {
            try
            {
                var bmp = new BitmapImage();
                bmp.BeginInit();
                bmp.CacheOption = BitmapCacheOption.OnLoad;
                bmp.UriSource = new Uri(_avatarPath, UriKind.Absolute);
                bmp.EndInit();
                AvatarImage.Source = bmp;
                AvatarImage.Visibility = Visibility.Visible;
                AvatarLetter.Visibility = Visibility.Collapsed;
                return;
            }
            catch { /* fall through */ }
        }
        AvatarImage.Source = null;
        AvatarImage.Visibility = Visibility.Collapsed;
        AvatarLetter.Visibility = Visibility.Visible;
        var name = UserNameBox.Text?.Trim();
        AvatarLetter.Text = string.IsNullOrEmpty(name) ? "FQ" : name[..1].ToUpperInvariant();
    }

    private void AvatarPreview_Click(object sender, System.Windows.Input.MouseButtonEventArgs e)
        => ChangeAvatar_Click(sender, e);

    private void ChangeAvatar_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new Microsoft.Win32.OpenFileDialog
        {
            Title = "选择头像图片",
            Filter = "图片|*.png;*.jpg;*.jpeg;*.bmp;*.gif|所有文件|*.*",
            CheckFileExists = true
        };
        if (dlg.ShowDialog() != true) return;
        _avatarPath = dlg.FileName;
        RefreshAvatarPreview();
    }

    private void BrowseDownload_Click(object sender, RoutedEventArgs e)
    {
        using var dlg = new Forms.FolderBrowserDialog
        {
            Description = "选择接收文件保存目录",
            SelectedPath = DownloadDirBox.Text,
            ShowNewFolderButton = true
        };
        if (dlg.ShowDialog() == Forms.DialogResult.OK)
            DownloadDirBox.Text = dlg.SelectedPath;
    }

    private void BrowseChatDir_Click(object sender, RoutedEventArgs e)
    {
        using var dlg = new Forms.FolderBrowserDialog
        {
            Description = "选择聊天记录（SQLite）存放目录",
            SelectedPath = ChatDirBox.Text,
            ShowNewFolderButton = true
        };
        if (dlg.ShowDialog() == Forms.DialogResult.OK)
            ChatDirBox.Text = dlg.SelectedPath;
    }

    private void Cancel_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
        Close();
    }

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        var name = UserNameBox.Text?.Trim();
        if (string.IsNullOrEmpty(name))
        {
            System.Windows.MessageBox.Show("用户名不能为空", "FeiQ 2026");
            return;
        }
        if (name.Length > 32)
        {
            System.Windows.MessageBox.Show("用户名最多 32 字符", "FeiQ 2026");
            return;
        }

        _settings.UserName = name;
        _settings.AvatarPath = _avatarPath;
        var url = ServerUrlBox.Text?.Trim();
        if (!string.IsNullOrEmpty(url))
            _settings.LastServerUrl = url;
        _settings.DownloadDir = DownloadDirBox.Text?.Trim() ?? _settings.DownloadDir;
        _settings.ChatDbDir = ChatDirBox.Text?.Trim() ?? _settings.ChatDbDir;
        _settings.Save();

        DialogResult = true;
        Close();
    }
}
