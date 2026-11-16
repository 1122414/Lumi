# Lumi · 安卓 M1

后置 1×、竖屏、3:4 取景。点击有纹理的小物体，琥珀色标记表示分析帧中匹配到的位置。看不清时立刻隐藏；短暂模糊后，只有附近连续两帧明确匹配原图案才重画。超时或位置不唯一时需要重新点选。此工具只验证坐标与拍照边界，不提供摄影指导。

## 构建

需要 JDK 21、Android SDK Platform 36、Build Tools 36.0.0。通过 Android Studio、环境变量 `ANDROID_HOME` 或本机的 `local.properties` 指定 SDK 位置。本机 Android Studio 的 JDK 可用：

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。连接设备后用 `./gradlew :app:installDebug` 安装。

## 真机验收

1. 拒绝相机权限应显示解释；允许后打开后置相机。在边缘附近选一处清晰的文字或图案（避开完全贴边处），平移、转动手机，检查标记是否始终贴在同一处；遮挡、选纯色或重复纹理时不应保留假标记。
2. 稳住手机，在取景器四条边附近放可辨认物体。按快门，在系统相册中看整张照片，不要用填满屏幕的放大视图。核对四边各切到哪个物体；坐标标记和调试信息不应写进照片。Android 8/9 首次保存还需要存储权限。
3. 转动设备，再切后台返回；若窗口大小、显示方向或相机会话变化，旧标记应清空。重新点选中心和靠边位置，重复前两项。记录机型、系统版本及角落的分析尺寸、旋转角、分析帧率和分析耗时。

分析帧率指实际分析的帧数，不是预览刷新率；耗时指单帧的分析开销，不含显示延迟。自动测试覆盖数学映射和受控图像序列，不能替代真机的光学、裁切和生命周期验收。

已有快门前、快门后两张预览和保存的原图时，可用[四边离线比对工具](tools/m1_framing/README.md)分别测量左、右、上、下的偏差；画面移动或参照不足会显示“无法判断”。
