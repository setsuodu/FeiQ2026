Android 应用图标
================
当前已接好 adaptive icon：
  mipmap-anydpi-v26/ic_launcher.xml  -> background + foreground
  drawable/ic_launcher_foreground.xml (矢量占位)
  values/colors.xml -> ic_launcher_background

你本地准备图片后，推荐两种方式任选其一：

方式 A（推荐，Android Studio）：
  右键 res -> New -> Image Asset -> 选你的 PNG/SVG，生成全密度 mipmap。

方式 B（手动）：
  把各密度 PNG 放到：
    mipmap-mdpi/ic_launcher.png      (48x48)
    mipmap-hdpi/ic_launcher.png      (72x72)
    mipmap-xhdpi/ic_launcher.png     (96x96)
    mipmap-xxhdpi/ic_launcher.png    (144x144)
    mipmap-xxxhdpi/ic_launcher.png   (192x192)
  可选 round：同目录 ic_launcher_round.png
  并更新 mipmap-anydpi-v26/ic_launcher.xml 的 foreground 指向 @mipmap/ic_launcher
  或继续用 drawable 矢量/PNG 作为 foreground。

Manifest 已使用 android:icon="@mipmap/ic_launcher"。
