# Lumi · 安卓 M2 构图测试

本步使用后置 1×、竖屏、单人。测试者原地摆好想要的构图，单人脸和手机姿态可用后按「记住这个构图」。轻微转动或仰俯手机，跟随绿环和连线回到中心白点。停稳出现「可以拍」后由用户按快门；其他状态也可正常拍照。再次按「记住这个构图」会替换本次目标。

遮挡人脸、快速掠过目标或分析停更时不应保留「可以拍」。退到后台、显示方向或取景尺寸变化后，需要重新记录。人脸模型随 APK 打包；跟踪 ID 重建后也需要重新记录。此入口不自动推荐构图，不验证挪步。

## 构建

需要 JDK 21、Android SDK Platform 36、Build Tools 36.0.0。通过 Android Studio、环境变量 `ANDROID_HOME` 或本机的 `local.properties` 指定 SDK 位置。本机 Android Studio 的 JDK 可用：

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。连接设备后用 `./gradlew :app:installDebug` 安装。

M1 的点选跟踪器仅保留为调试代码；[四边离线比对工具](tools/m1_framing/README.md)仍可单独运行。
