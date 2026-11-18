# Lumi · 安卓 M3 推荐构图

后置竖屏相机默认 1×。取景器底部的变焦轮盘可左右拖动，松手保持当前倍率（例如 2.8×）。点轮盘下缘的数字可直达关键倍率，也可以在取景器里双指连续缩放。轮盘范围由 CameraX 提供，快捷倍率按后置镜头公开的焦距和传感器尺寸换算。PKX110 另保留原厂拍照界面确认的 4× 快捷档。对准想拍的人或物件后点一次「推荐构图」，相机会发送当前倍率下第一张与手机姿态同步的新画面。模型选定主体和推荐取景中心，快门上方显示十字以内的理由。引导期间改变倍率会清除旧构图，可以在新倍率下拍照或重新推荐。

蓝色虚线标出手机在当前画面中确认的主体外缘。认出完整主体后，绿环根据它在当前画面和推荐画面中的位置计算。跟着绿环原地转动或仰俯手机，转过头时绿环会换到白点另一侧。核对背景期间也能跟随绿环。主体本应在画面内却无法确认时，引导暂停。手机明显转开且原主体已在画面外时，边缘绿环给出返回方向。主体完整、背景相符，回到推荐构图并停稳时会提示「可以拍」，由你按快门。

前景分割在手机上运行，使用 MediaPipe Tasks Vision 0.10.35 和随应用打包的 Google Magic Touch v1 模型。模型大小 6,227,884 字节，SHA-256 为 `e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25`。[模型来源](https://storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float32/1/magic_touch.tflite) · [Google 模型卡](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MagicTouch.pdf) · [Apache 2.0 许可](app/src/main/assets/magic_touch.LICENSE.txt)

推荐图仅在内存中处理。模型可以推荐保留当前构图，此时目标就在白点处，仍需核对画面并停稳。方案被拒绝时，快门上方会显示具体原因，例如「模型对这次构图把握不足」或「推荐角度会裁掉主体」。请求期限为 20 秒，再次点推荐会替换上次请求。退到后台或相机会话重建后需重新推荐。开发者可在 debug 版右上角点「调试」，选择「记住这个构图」离线检查绿环。

## 在手机上配置模型

点取景器上方的「模型」，填写接口地址、模型名和 API key。接口需支持 Chat Completions 和内嵌 JPEG 的 `image_url`。填到 `/v1` 会自动追加 `/chat/completions`，也可填完整接口地址。点「测试连接」会发送一张内置的 96 × 96 苹果图，面板显示结果和耗时。点「保存」会清除当前推荐，下一次推荐立即使用新配置。

「未配置」表示缺少有效配置。「可用」表示配置可以发起请求，「上次失败」表示这组配置最近一次连接或模型回复校验失败。设置面板里的测试可以确认当前是否连通。API key 默认隐藏，临时显示 10 秒后自动隐藏，设置面板禁止系统截图。保存的密钥经 Android Keystore 的 AES-GCM 密钥加密。

release 版使用 HTTPS。debug 版也支持 HTTP。手机和电脑在同一可信局域网时，可以填写电脑的局域网地址，例如 `http://192.168.1.20:3425/v1`，电脑服务需监听该地址并允许手机访问。使用 USB 时，执行 `adb reverse tcp:3425 tcp:3425`，手机填写 `http://127.0.0.1:3425/v1`。

应用内保存的配置优先。首次使用且从未保存过配置时，debug 版可从构建时的 `local.properties` 取得以下默认值。保留原有的 `sdk.dir`。这些默认值会打包进 debug APK，请勿分发含个人密钥的 APK 或提交 `local.properties`。release 版从空配置开始。

```properties
LUMI_LLM_BASE_URL=https://your-service.example/v1
LUMI_LLM_MODEL=your-vision-model
LUMI_LLM_API_KEY=your-local-test-key
```

使用 `workbuddy/deepseek-v4.1-flash` 时，Lumi 按模型名关闭长推理。调试版的 `LumiRecommendation` 日志与私有 `recommendation_outcomes` 记录包含模型名、接口主机名以及准备、模型和本地核对耗时。最近两次判断保存在手机上，首次显示绿环后补记从点击到显示的总耗时。

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
