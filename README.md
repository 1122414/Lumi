# Lumi · 安卓 M3 推荐构图

后置 1×、竖屏，对准想拍的人或物件后点一次「推荐构图」。相机会等到点击后第一张能与手机姿态同步的新画面，向配置的视觉模型发送这张图。模型选定一个主体、它与背景的构图和一个取景中心。快门上方显示十字以内的理由。

蓝色虚线标出手机在当前画面中确认的主体外缘。认出完整主体后，绿环根据它在当前画面和推荐画面中的位置计算。跟着绿环原地转动或仰俯手机，转过头时绿环会换到白点另一侧。核对背景期间也能跟随绿环。主体本应在画面内却无法确认时，引导暂停。手机明显转开且原主体已在画面外时，边缘绿环给出返回方向。主体完整、背景相符，回到推荐构图并停稳时会提示「可以拍」，由你按快门。

前景分割在手机上运行，使用 MediaPipe Tasks Vision 0.10.35 和随应用打包的 Google Magic Touch v1 模型。模型大小 6,227,884 字节，SHA-256 为 `e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25`。[模型来源](https://storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float32/1/magic_touch.tflite) · [Google 模型卡](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MagicTouch.pdf) · [Apache 2.0 许可](app/src/main/assets/magic_touch.LICENSE.txt)

推荐图仅在内存中处理。再次点推荐会替换上次请求；超时（20 秒）、无可信结果或推荐画面会裁掉选中的主体时，不显示推荐环。退到后台或相机会话重建后需重新推荐。开发者可在 debug 版右上角点「调试」，选择「记住这个构图」离线检查绿环。

## 本地配置（仅 debug）

在项目根目录已忽略的 `local.properties` 中保留原有的 `sdk.dir`，另外填写以下三项，再重新构建、安装。接口需支持 Chat Completions 和内嵌 JPEG 的 `image_url`；远程服务使用 HTTPS。

```properties
LUMI_LLM_BASE_URL=https://your-service.example/v1
LUMI_LLM_MODEL=your-vision-model
LUMI_LLM_API_KEY=your-local-test-key
```

以 `/v1` 结尾的地址会追加 `/chat/completions`，也可填完整接口地址。缺少任一项时，「推荐构图」不可用，取景器下方会显示原因。密钥会随本机 debug APK 一起打包，不要分享带密钥的 APK，也不要提交 `local.properties`。release 不注入这些配置。

模型服务运行在 USB 所连的电脑上时，debug 版也接受 `http://127.0.0.1`。例如电脑监听 3425 端口：设 `LUMI_LLM_BASE_URL=http://127.0.0.1:3425/v1`，在电脑执行 `adb reverse tcp:3425 tcp:3425`。手机就能通过 USB 访问该服务，无需让电脑监听局域网。重新连接手机后可用 `adb reverse --list` 检查转发。

使用 `workbuddy/deepseek-v4.1-flash` 时，Lumi 关闭长推理并将输出上限设为 1280 tokens，给主体名称和轮廓坐标留出空间。请求期限为 20 秒。

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
