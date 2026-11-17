# Lumi · 安卓 M3 推荐构图

后置 1×、竖屏，对准一个人后点「推荐构图」。每点一次，Lumi 只向配置的视觉模型发送当时的一张取景图。可以在等待时转动手机；结果返回后，跟着绿环转动或仰俯，直到它与中心白点合拢。停稳出现「可以拍」后自己按快门，其他状态也可以拍照。

推荐图仅在内存中处理。再次点推荐会替换上次请求；超时（20 秒）、无可信结果或人脸会出画时，不显示推荐环。退到后台或相机会话重建后需重新推荐。画面上方的「记住这个构图」仍可离线检查引导，不调用模型。

## 本地配置（仅 debug）

在项目根目录已忽略的 `local.properties` 中保留原有的 `sdk.dir`，另外填写以下三项，再重新构建、安装。接口必须支持 HTTPS、Chat Completions 和内嵌 JPEG 的 `image_url`。

```properties
LUMI_LLM_BASE_URL=https://your-service.example/v1
LUMI_LLM_MODEL=your-vision-model
LUMI_LLM_API_KEY=your-local-test-key
```

以 `/v1` 结尾的地址会追加 `/chat/completions`，也可填完整接口地址。缺少任一项时，「推荐构图」不可用，取景器下方会显示原因。密钥会随本机 debug APK 一起打包，不要分享带密钥的 APK，也不要提交 `local.properties`。release 不注入这些配置。

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
