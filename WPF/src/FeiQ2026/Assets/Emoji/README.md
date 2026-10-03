Emoji 素材 + 映射表
==================

put emoji png files here
put emoji_map.json here

【目录结构示例】
Assets/Emoji/
  emoji_map.json          ← 映射表（必须）
  1f600.png
  1f602.png
  1f44d.png
  2764.png
  ...

【emoji_map.json 格式】（你给的标准）
[
  {
    "category_name": "face",
    "category_title": "表情与人物",
    "emojis": [
      { "char": "😀", "code": "1f600", "image": "1f600.png", "shortcode": "[:smile:]" },
      { "char": "😂", "code": "1f602", "image": "1f602.png", "shortcode": "[:joy:]" },
      { "char": "👍", "code": "1f44d", "image": "1f44d.png", "shortcode": "[:like:]" }
    ]
  }
]

【行为说明】
- 表情面板：按分类显示，有图显示图片
- 点击插入：仍插入 Unicode 字符（兼容飞秋2013 / Android）
- 聊天气泡：自动把 Unicode 替换成对应图片渲染
- 输入框：普通 TextBox 限制，仍显示系统 Unicode（点选后能看到）
- shortcode（如 [:smile:]）目前仅作提示，发送不走 shortcode 协议

【注意】
image 字段写相对文件名即可，程序会在本目录查找。
