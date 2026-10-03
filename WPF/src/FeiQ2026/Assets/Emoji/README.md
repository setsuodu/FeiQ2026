# Emoji 素材包目录
================

当前聊天窗口表情选择器默认使用 **Unicode 表情**，无需任何图片即可正常使用。

若你想改用图片表情（如微信风格 png），请把素材放到本目录：

  put emoji png files here

建议命名示例：
  smile.png
  cry.png
  thumbup.png
  ...

扩展方式（可选）：
1. 将 png 放入本目录
2. 在 ChatWindow.xaml.cs 的 Emoji_Click 中读取本目录图片并生成按钮
3. 发送时使用约定格式，例如 [emoji:smile]

目前仅预留位置，未实现图片表情加载逻辑。

# 脚本拷贝png

cd /d D:\GitHub\[Workspace]\FeiQ2026\WPF
python pick_emoji.py