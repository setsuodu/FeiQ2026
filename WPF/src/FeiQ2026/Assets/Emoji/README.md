Emoji 素材目录
==============

put emoji png files here

【必须命名规则】
文件名 = Unicode codepoint（小写 hex），扩展名 .png / .jpg / .webp

示例（对应面板 40 个）：
  1f600.png   → 😀  grinning
  1f601.png   → 😁  grin
  1f602.png   → 😂  joy
  1f923.png   → 🤣  rofl
  1f60a.png   → 😊  blush
  1f60d.png   → 😍  heart_eyes
  1f618.png   → 😘  kissing_heart
  1f61c.png   → 😜  stuck_out_tongue_winking
  1f914.png   → 🤔  thinking
  1f60e.png   → 😎  sunglasses
  1f622.png   → 😢  cry
  1f62d.png   → 😭  sob
  1f621.png   → 😡  rage
  1f44d.png   → 👍  thumbsup
  1f44e.png   → 👎  thumbsdown
  1f44f.png   → 👏  clap
  1f64f.png   → 🙏  pray
  2764.png    → ❤️  heart          （也支持 2764-fe0f.png）
  1f494.png   → 💔  broken_heart
  1f525.png   → 🔥  fire
  1f389.png   → 🎉  tada
  2728.png    → ✨  sparkles
  1f4af.png   → 💯  100
  2705.png    → ✅  white_check_mark
  274c.png    → ❌  x
  2b50.png    → ⭐  star
  1f31f.png   → 🌟  star2
  1f4a1.png   → 💡  bulb
  1f4cc.png   → 📌  pushpin
  1f4ce.png   → 📎  paperclip
  1f4f7.png   → 📷  camera
  1f3b5.png   → 🎵  musical_note
  1f3ac.png   → 🎬  clapper
  1f4c1.png   → 📁  file_folder
  1f4bb.png   → 💻  computer
  1f4f1.png   → 📱  iphone
  2615.png    → ☕  coffee
  1f37a.png   → 🍺  beer
  1f355.png   → 🍕  pizza
  1f381.png   → 🎁  gift

【说明】
- 映射表在代码 Services/EmojiCatalog.cs（Unicode / codepoint / shortName）
- 面板有图就显示图片，没图回退 Unicode 字符
- 发送时仍插入 Unicode（兼容飞秋2013、Android），不使用 [emoji:smile] 协议
- 若你素材是中文名 / 序号名，先用之前的 pick_emoji.py 脚本重命名成上面这种

【运行时查找顺序】
1. 程序目录/Assets/Emoji/
2. 程序目录/Emoji/
3. 开发时源码 Assets/Emoji/

# 脚本拷贝png

cd /d D:\GitHub\[Workspace]\FeiQ2026\WPF
python pick_emoji.py No newline at end of file