<div align="center">

# Lumi

**Android 实时 AI 摄影导演相机**  
*在按下快门之前，帮你看懂主体、焦段与构图，一步到位拍出好照片。*

<p>
  <a href="./README.md">English</a> ·
  <a href="./README.zh-CN.md"><strong>简体中文</strong></a>
</p>

<p>
  <img src="https://img.shields.io/badge/平台-Android%208.0%2B%20(API%2026%2B)-3DDC84?logo=android&logoColor=white" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/语言-Kotlin-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/界面-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/相机-CameraX%201.5-0F9D58" alt="CameraX" />
  <img src="https://img.shields.io/badge/端侧视觉-ML%20Kit%20%2B%20MediaPipe-FF6F00" alt="ML Kit + MediaPipe" />
</p>

</div>

---

## 为什么做 Lumi？

市面上的大多数相机应用都在卷 **拍后修图** —— 上百款滤镜、重度磨皮美颜，或者把没构好图的废片送到云端重绘精修。

**Lumi 把重心放在「按下快门之前」。**  
Lumi 采用 **「云端视觉大模型做摄影大脑 + 端侧 60fps 传感器与视觉跟踪做稳态执行」** 的务实端云架构：不堆砌难懂的摄影术语，不塞满杂乱网格线，而是针对当前画面给出 **一条最值钱、可立刻执行的取景引导**，用丝滑的绿环把你稳稳带到更好看的构图位置。

---

## 核心特性

| 核心能力 | 体验说明 |
| :--- | :--- |
| **端云协同 · 秒级推荐 + 60fps 稳态引导** | 端侧 ML Kit 先找出人脸、多人合照与前景主体编号框；云端视觉模型只需返回超短 JSON 选择题（实测约 1 秒左右出绿环），随后由手机陀螺仪四元数投影以 60fps 稳定驱动绿环引导，不抖动、不闪断。 |
| **轻点即选主体 + 联动对焦测光** | 在取景器中轻点你想拍的人或物体，即可立刻锁定拍摄意图，同时自动触发 CameraX `FocusMeteringAction` 对目标主体对焦与测光，并把你的点选标记同步给云端模型。 |
| **单人 / 多人合照 / 静物全覆盖** | 自动识别单人人像、多人合照与前景静物；端侧基于 MediaPipe Magic Touch 勾勒蓝色主体轮廓，并在低对比或细长物体分割为空时自动启用候选框兜底，显著提升实拍成功率。 |
| **三维防漂移锚定 + 近景视差跟踪** | 端侧 ~1ms/帧轻量 2D 颜色跟踪器实时补偿主体微动与近景视差；同时引入 `12°` 仰俯角视角锚定保护，避免低头俯拍或大幅移开手机时出现绿环误重合。 |
| **光学变焦轮盘 + 一键焦段建议** | 基于 Camera2 真实焦距与传感器尺寸自动换算设备光学档位（如 `0.6× / 1× / 2× / 3×`，并适配 PKX110 `4×` 快捷档）。当模型建议切换更合适的焦段时，取景器弹出「建议切至 `2×`」一键按钮，切换后**保留当前构图引导无缝衔接**。 |
| **零延迟本地构图预设** | 底部提供 `智能` / `三分（左/右）` / `对称` / `紧凑` / `留白` 构图预设，在手机本地即时重算绿环位置，无需等待网络往返。 |
| **极简水平仪 · 震动反馈 · 自动拍** | 中心白点两侧提供极简水平短线，横滚角进入 `±2.5°` 时与可拍判定同步变绿（所见即所得）；构图到位触发轻震提示，并可选开启 **自动拍**（停稳 `450ms` 自动触发快门；若仍有未切换的推荐倍率则自动暂停防误拍）。 |
| **隐私与凭据安全设计** | 推荐用图仅在内存中处理；API Key 经 Android Keystore 的 AES-GCM 密钥加密保存，界面默认隐藏且设置弹窗禁止系统截图。 |

---

## 工作流程

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                          取景器（60 fps 实时预览）                        │
│     CameraX 预览 · 精准变焦轮盘 · 极简水平仪 · 轻点选主体 · 本地构图预设     │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │
        ┌───────────────────────┴───────────────────────┐
        ▼                                               ▼
┌──────────────────────────────┐        ┌──────────────────────────────┐
│     端侧视觉与姿态底座         │        │      云端视觉摄影大脑 (VLM)   │
│  • ML Kit 人脸/合照/物体候选框 │ 512px  │  • 选择最佳主体编号           │
│  • 点选标记 + 真实光学变焦档位 ├───────►│  • 决策构图意图与景别         │
│  • MediaPipe Magic Touch 抠图 │ <───┐  │  • 可选推荐更合适的光学倍率   │
│  • ~1ms/帧轻量颜色跟踪补偿    │     │  │  • 输出十字以内的简洁理由     │
│  • 四元数姿态投影与防漂移锚定  │ JSON│  └──────────────────────────────┘
└──────────────┬───────────────┘     │
               └─────────────────────┘
                                │
                                ▼
       丝滑绿环引导 + 蓝色主体轮廓 + 到位轻震反馈 + 可选停稳自动拍
```

1. **对准或轻点主体**：举起手机对准画面（或轻点你想突出的主体），点击 **「推荐构图」**。
2. **端侧毫秒级反馈**：请求发出瞬间，界面先显示本地候选框或对焦圈，消除等待黑盒感。
3. **跟随绿环对齐**：顺着绿环方向微调角度并调平水平条，让绿环与中心白点合拢。
4. **定格成片**：收到轻震与「可以拍」提示后按下快门，或开启右上角 **「自动拍」** 停稳自动成片。

---

## 快速开始

### 环境要求

- **JDK**：17 及以上（推荐直接使用 Android Studio 内置 JDK 21 JBR）
- **Android SDK**：Platform `36`、Build Tools `36.0.0`
- **运行设备**：Android 8.0 及以上（`minSdk 26`），建议使用真机后置相机体验

### 构建、测试与安装

```bash
# macOS 下可直接使用 Android Studio 内置 JDK
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# 运行单元测试、编译 Debug APK 并执行 Lint 检查
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

# 连接手机后直接安装到真机
./gradlew :app:installDebug
```

构建产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

---

## 配置视觉模型

Lumi 支持任意兼容 **OpenAI Chat Completions** 且支持内嵌 Base64 JPEG `image_url` 的视觉大模型接口。

### 方式一：应用内热配置（推荐）

1. 打开 Lumi，点击取景器顶部的 **「模型」**。
2. 填写 **接口地址**（填到 `/v1` 会自动追加 `/chat/completions`，也可填完整地址）、**模型名** 与 **API Key**。
3. 点击 **「测试连接」**（发送内置 `96 × 96` 探测图检验连通性与耗时），确认可用后点击 **「保存」** 即可立即生效。

> **网络传输与局域网/USB 调试：**  
> Release 版强制使用 `HTTPS`；Debug 版额外支持可信局域网 `HTTP`（如 `http://192.168.1.20:3425/v1`）或 USB 端口转发 `adb reverse tcp:3425 tcp:3425`（手机填写 `http://127.0.0.1:3425/v1`）。

### 方式二：Debug 构建默认值（`local.properties`）

本地开发调试时，可在仓库根目录未纳入版本控制的 `local.properties` 中追加默认值（保留原有 `sdk.dir`）：

```properties
LUMI_LLM_BASE_URL=https://your-service.example/v1
LUMI_LLM_MODEL=your-vision-model
LUMI_LLM_API_KEY=your-local-test-key
```

> **安全提醒：** 请勿提交 `local.properties`，也不要向外分发包含个人密钥的 Debug APK。应用内保存的配置始终优先，Release 版默认从空配置启动。

---

## 演进路线图

- [x] **阶段 S1 — 端云协同体验基线（已完成）**
  - 端侧人脸/合照/物体候选框编号与取景器轻点选主体
  - 光学焦段建议与切倍率后绿环无缝衔接
  - 极简水平仪、到位轻震反馈、自动拍开关与仰俯角防漂移锚定
  - `512px` 极简协议提速 + 主体联动 AF/AE 对焦测光
- [x] **阶段 S2 — 实时摄影导演（已完成）**
  - 单点最高优先级执导协议（构图/焦段、背景避障、光线利用、人像一句话姿势提示）
  - 三档 AI 主动程度模式（`安静` / `主动` / `手动`）
  - 拍后 3 秒轻复盘 + 基于主体掩码的端侧零成本美学二次构图裁剪（保留原图底片）
- [x] **阶段 S3 — 空间护城河与审美记忆（已完成）**
  - 跨视差「挪步」机位引导（`左/右/靠近/后退` 实时背景锚点视差进度条，解决背景柱子/杂物遮挡）
  - 拍后四维「好照片」评估卡（主体突出 × 画面平衡 × 背景干净 × 自然度）+ 本地轻量个人审美偏好记忆（自动注入云端先验，可随时重置）
- [x] **阶段 S4 — 专业光影与参数控制层（已完成）**
  - 实时物理光影遥测（Camera2 `ISO` / 快门 / 光圈 / `EV` + 环境光 `Lux` + 逐帧高光/暗部溢出与逆光比），自动注入云端导演决策
  - 三档曝光控制模式（`自动` / `AI自动` / `M手动`），并与主动程度联动（`手动` 下弹出「应用」确认胶囊，`主动` 下自动平滑下发并支持一键「还原」）
  - `M手动` 逐项 `AUTO` 半自动优先模式（`EV / ISO / S / WB` + 硬件自适应光圈 `F`），支持单锁快门自动算 ISO 防黑屏，以及一键「抄 AI 参数」「全 AUTO」
- [x] **阶段 S5 — 参考图复刻与超越复刻导演（已完成）**
  - 可选导入参考图（缩略图 + 一键开关 25% 半透明叠图与透明度微调 + 拍摄处方卡），与常规拍摄完全解耦
  - 同机位人像复刻提供可直接张嘴复述的动作修正口令，并额外给出「超越复刻」升级建议（抄作业但不止抄作业）

完整的产品定位、竞品分析与阶段规划见 [`plan_/11.26/2026-11-26_Lumi下一步产品与技术战略.md`](plan_/11.26/2026-11-26_Lumi下一步产品与技术战略.md)。

---

## 项目结构

```text
Lumi/
├── app/src/main/java/com/lumi/camera/
│   ├── camera/        # CameraX 相机会话、光学变焦档位、传感器姿态与坐标映射
│   ├── guidance/      # 四元数旋转引导、横滚水平角与停稳到位判定
│   ├── shotplan/      # 候选框协议、语义构图策略、VLM 客户端与三维目标锚定
│   ├── vision/        # ML Kit 检测、MediaPipe 前景分割与轻量 2D SubjectTracker
│   └── ui/            # Jetpack Compose 取景器、变焦轮盘、模型配置与诊断视图
├── app/src/test/      # 纯 Kotlin 单元测试（光学换算、引导算法、协议解析、防漂移与兜底）
├── tools/m1_framing/  # 离线四边取景比对工具
├── plan_/             # 产品战略与阶段架构设计文档
└── AGENTS.md          # 协作开发与 Conventional Commit 提交规范
```

---

## 端侧模型与开源许可说明

前景主体分割完全在手机本地运行，基于 **MediaPipe Tasks Vision 0.10.35** 及随应用打包的 **Google Magic Touch v1** 模型：

- **模型文件**：`magic_touch.tflite`（`6,227,884` 字节）
- **SHA-256**：`e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25`
- **相关链接**：[模型来源](https://storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float32/1/magic_touch.tflite) · [Google 模型卡](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MagicTouch.pdf) · [Apache 2.0 许可](app/src/main/assets/magic_touch.LICENSE.txt)

仓库内另保留离线取景评估工具 [`tools/m1_framing`](tools/m1_framing/README.md) 可单独运行。
