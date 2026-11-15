# 光年相机（Android）

一台能用的相机：**实时滤镜 · 拍照 · 录像 · 参数可调**。底层还带着测色能力（白参考 → ΔE00 判定），
但那不再是主界面 —— 现在打开就是一个相机。

- 包名：`com.spectradesk.camera`
- 目标：Android 8.0+（minSdk 26）
- 实测：Motorola XT2551-3 / Android 16、vivo V2352A / Android 16、
  Android 模拟器 API 34（x86_64，`Zhihui_API_34`）
- 大小：release 1.95 MB（开启 R8 代码压缩 + 资源压缩，原为 12.4 MB；
  反编译显示未压缩时 17,880 个类里只有 54 个是本项目代码）

```
spectradesk-android/
  app/build/outputs/apk/release/app-release.apk   ← 装机用这个
  app/build/outputs/apk/debug/app-debug.apk       ← 带日志
  branding/                                        ← 图标源文件与生成脚本
  screenshots/                                     ← 界面截图（含 lightyear-* 实机验证）
  build_apk.bat                                    ← 一键构建+安装
  run_tests.py                                     ← 跑单元测试（80 项）
```

![icon](screenshots/00-icon.png)

图标语义：三个彩色采样点连成三角形，**Δ 三角形呼应 ΔE 色差**，中心准星表示精确测量。
由 `branding/generate_icons.py` 生成（8× 超采样 + 自动验证安全区），已接入 adaptive icon。

## 一、功能

| 功能 | 状态 | 说明 |
|---|---|---|
| 实时滤镜 | ✅ | 8 种观感，GPU 片元着色器，30 fps |
| 滤镜强度 | ✅ | 0–100% 连续可调，实时生效 |
| **手动调色** | ✅ | 10 项滑杆 + 7 个预设，参考剪映/醒图 |
| **前后置切换** | ✅ | 一键切换，前置自动镜像，方向已修正 |
| **画幅比例** | ✅ | 16:9 / 4:3，取景器+预览+照片三者同比例 |
| **水印** | ✅ | 四角/版式/字段可选，合成进 JPEG |
| **点按对焦** | ✅ | 点取景器任意位置对焦，显示对焦框 |
| **手动对焦** | ✅ | 屈光度滑杆，设备支持时可用 |
| 拍照 | ✅ | 16:9 或 4:3，存入相册 `Pictures/LightYear` |
| 录像 | ✅ | H.264 MP4，1080×1920，存入 `Movies/LightYear` |
| 录音 | ✅ | 可开关；未授权时静默降级为无声录像 |
| 变焦 | ✅ | 滑杆 + 快捷档位（0.6/1/2/3/5/10×） |
| 曝光补偿 | ✅ | ±EV，步长按设备实测（这台是 1/10 EV） |
| 超广角 | ⚠️ | 代码已实现，但这台 vivo 未向三方暴露该相机 |
| 横竖屏自适应 | ⚠️ | 界面锁竖屏；横拍时照片 EXIF 方向正确，见第七节 |

实测截图：

| 实时滤镜 | 参数面板（全） | 调色 · 胶片预设 |
|---|---|---|
| ![live](screenshots/lightyear-01-live.png) | ![params](screenshots/lightyear-20-params-full.png) | ![film](screenshots/lightyear-21-grade-film.png) |

画幅切换：16:9 铺满取景器，4:3 上下留黑（同系统相机行为）：

| 16:9（默认） | 4:3 | 前置（镜像） |
|---|---|---|
| ![169](screenshots/lightyear-30-16x9.png) | ![43](screenshots/lightyear-31-4x3.png) | ![front](screenshots/lightyear-32-front-live.png) |

水印：预览是 overlay，存盘时用同一套几何合成进 JPEG：

| 取景器里的水印预览 | 成片（水印已烧进像素） |
|---|---|
| ![wm preview](screenshots/lightyear-41-watermark-preview.png) | ![wm photo](screenshots/lightyear-40-watermark-photo.jpg) |

**滤镜确实烧进了视频**，不是只在取景器里做样子。从录好的 MP4 抽帧：

| 黑白滤镜录制的视频 | 冷调滤镜录制的视频 |
|---|---|
| ![mono](screenshots/lightyear-06-video-mono.png) | ![cool](screenshots/lightyear-07-video-cool.png) |

## 一·补一、三条流必须同比例（"镜头被拉长"的真因）

相机应用最容易出错的地方是**预览流 / 照片 / 取景器 View 三者比例不一致**。
任意两者不同，用户框住的和他拍到的就不是同一块画面。

实测踩到的坑，两个都表现为"镜头拉长了"：

| 现象 | 根因 | 量化 |
|---|---|---|
| 取景器视野变窄、像加了变焦 | 预览流默认 4:3（1632×1224），取景器是 0.569 竖屏，shader 只能横向裁 | **保留水平视野 75.9%，等效 1.32× 变焦** |
| 成片比看到的多出上下一条 | ImageCapture 默认给传感器最大 4:3（4096×3072） | 照片 1.333 vs 取景器 0.5624 |

修法是让三条流都跟着 `FrameAspect` 走：

```kotlin
// 预览与照片都用同一个 AspectRatioStrategy
ResolutionSelector.Builder()
    .setAspectRatioStrategy(if (ratio == 16/9) RATIO_16_9_FALLBACK_AUTO_STRATEGY
                            else RATIO_4_3_FALLBACK_AUTO_STRATEGY)
```

16:9 时预览流拿到 1920×1080，取景器 1176×2091（比例 0.5624），
裁切量降到 1% 以内，**等效变焦回到 1.00×**；照片也变成 4096×2304。

### 同类软件是这么做的（REA 静态分析实证）

InstaShot 有 `com.camerasideas.instashot.widget.AspectRatioFrameLayout`，
`onMeasure` 的逻辑就是按固定比例重算 View 尺寸：

```java
float f15 = (this.aspectRatio / (measuredWidth / measuredHeight)) - 1.0f;
if (Math.abs(f15) <= 0.01f) return;          // 比例已匹配，不动
// 否则按 resizeMode 重算宽或高
super.onMeasure(makeMeasureSpec(newW, EXACTLY), makeMeasureSpec(newH, EXACTLY));
```

**结论：取景器是按比例约束测量的，不是自由填满。** 这正是本项目改成
`aspectRatio(portraitRatio)` 的依据 —— 不是拍脑袋，是照着同类应用的做法。

## 一·补二、切镜头必须释放旧处理器

"切前置没反应"的根因不是选错了相机 —— logcat 明确显示 `Camera ID: 1`（前置）
已连接。真正的原因是**旧的 GlFilterProcessor 没被释放**：

```
$ adb shell ps -T -p <pid> | grep -c gl-filter
2        ← 切一次镜头就多一个
```

`bind()` 每次都 `new` 一个处理器，但旧的那个自己的 HandlerThread、EGL 上下文、
以及挂在取景器 Surface 上的绘制全都还活着。两个处理器轮流往**同一个**
SurfaceView 上画：新相机（前置）的帧和旧相机（后置）的最后一帧在抢同一个
缓冲区，视觉上就是画面没变。

修法是在 `bind()` 开头显式释放旧处理器并摘掉取景器回调：

```kotlin
filterProcessor?.let { runCatching { it.release() } }
filterProcessor = null
previewView.onSurfaceAvailable = null
previewView.onSurfaceSizeChanged = null
```

修后 `grep -c gl-filter` 恒为 1，前置画面立刻变成前置视角。

## 一·补、调色为什么单独一层

`LiveFilter` 是**预设**（选一个名字，整张画面观感就定了），`ColorGrade` 是**滑杆**
（逐项微调，作用在预设之后的成品画面上）。两者串联，顺序和剪辑软件一致。

关键决定：**预设在线性光里算，滑杆在 sRGB 域里算**。

| 层 | 域 | 为什么 |
|---|---|---|
| 预设滤镜 | 线性光 | 色彩运算的物理正确域；测色仪器必须这样 |
| 手动调色 | sRGB | 滑杆是**感知**控制，用户拿它和剪映对比，而剪映在 sRGB 域里做 |

把同一个对比度公式搬进线性光，中灰会被推坏，用户拉一下就"觉得不对"。
所以分工是：预设保色彩保真，滑杆保操作手感。

这十项是照着参考应用的实际实现挑的。用 REA 静态分析 Google Photos 的 APK，
它自带的 filterfw 图元里就有 `ColorTemperatureFilter`、`ContrastFilter`、
`VignetteFilter`、`GrainFilter`、`SharpenFilter`、`SaturateFilter` ——
和这里的色温、对比度、暗角、颗粒、锐化、饱和度一一对应。

调色**永远不进测量**：测量读的是 `ImageCapture` 的原始输出，连 GL 管线都不经过。

## 二、实时滤镜怎么做的

### 数据流

```
Camera ──► Preview use case ──► SurfaceRequest
                                     │
                          自己建 SurfaceTexture (OES)
                                     ▼
                              fragment shader
                                     │
                       ┌─────────────┴─────────────┐
                       ▼                           ▼
               取景器 SurfaceView          录像编码器 Surface
                 （自己持有）              （CameraEffect 给）
```

预览和录像走**同一套 shader**，所以录下来的就是你在取景器里看到的。

### 为什么不直接用 PreviewView

上一版用 `PreviewView` + `CameraEffect(PREVIEW|VIDEO_CAPTURE|IMAGE_CAPTURE)`，
预览上下出现黑边。排查过程值得记下来，因为每一步都排除了一个错误假设：

1. **怀疑 GL 裁剪算错** → 打印矩阵，发现 `updateTransformMatrix` 返回的已经含裁剪
   （`scaleY = -0.785`，正好是 ViewPort 比例）。GL 是对的。
2. **怀疑 ViewPort 没生效** → 三次对照（有 ViewPort / 无 ViewPort / 改 targetRotation），
   黑边位置一模一样。不是 ViewPort 的事。
3. **怀疑自己的 GL 画错** → 把清屏色改成红色、把 shader 改成纯绿输出。
   红色从没出现，绿色铺满整个取景框。**GL 覆盖范围完全正常。**
4. **真正的原因**：接入 `SurfaceProcessor` 后，CameraX 交给 PreviewView 的裁剪信息是
   竖屏 `754×1280`，而 effect 的输出缓冲是横向 `1280×960`，两者互为转置。
   PreviewView 不知道画面已经被 GL 处理过，会**再套一次**自己的
   `PreviewTransformation`，把已经画满的画面按竖屏比例又做了一次信箱（letterbox）。

这个冲突在 PreviewView 内部无解。所以取景器换成自己持有 Surface 的 `PreviewSurfaceView`，
几何 100% 由 GL 管线决定，没有第二个人再动它。

### 两个踩过的坑

| 现象 | 原因 | 修法 |
|---|---|---|
| 预览全黑 | `surfaceCreated` 早于 GL 处理器就绪，事件被丢弃 | `PreviewSurfaceView` 记住当前表面，注册回调时补发一次 |
| 画面被拉长 | `u_matrix` 含 90° 旋转，填充裁剪用错了未旋转的纵横比 | 检测四分之一转，交换宽高再算裁剪 |
| 参数面板一开就黑边 | 面板挤压取景器 → SurfaceView 缓冲区重建 → 中间露出黑底 | 参数面板改成取景器内的浮层 |

## 三、测量能力（仍在，但不再是主界面）

手机相机只有三个宽通道（大致对应红/绿/蓝），**物理上不可能测出光谱**。
三通道到光谱的映射是无穷多解的，任何"重建出来的曲线"都是先验假设的产物，不是测量。

所以测量部分只做它真能做到的事：

| 能做 | 不能做 |
|---|---|
| 测颜色（CIE Lab） | 测光谱曲线 |
| 算色差（CIEDE2000） | 识别物质成分 |
| 判定合格/超差 | 同色异谱分析 |
| 导出 CSV / HTML 报告 | 绝对精度（无出厂标定） |

**滤镜与测量的边界是硬的**：`CameraEffect` 的 targets 只含 `VIDEO_CAPTURE`，
不含 `IMAGE_CAPTURE`。测量读的是 `ImageCapture` 的原始输出，滤镜永远进不了数字。
滤镜作用于"你看到的"和"你录下的"，测量走未处理的原图。

`FiltersTest` 里有一条测试专门钉死"只有 NONE 和 GRID 是 measurement-safe"。

## 四、安装

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\release\app-release.apk
& $adb shell am start -n com.spectradesk.camera/.MainActivity
```

或者直接把 APK 拷到手机点安装（需允许「未知来源」）。

> **release 用 debug 签名**。自己装没问题；要上应用商店必须换正式签名。

## 五、模拟器上验证

本项目在 Android 模拟器上完整验证过（`Zhihui_API_34`，Android 14 / API 34，x86_64）：

```powershell
# 启动（带摄像头映射，模拟器会把宿主摄像头当作相机）
$emu = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"
& $emu -avd Zhihui_API_34 -gpu host -camera-back webcam0 -no-snapshot-load
```

实测结论：

- GL 管线稳定 **30 fps**（`EGL_emulation: app_time_stats avg=33.3ms`）
- 预览 `1016×1724` 铺满取景器，无黑边
- 照片：`/sdcard/Pictures/LightYear/LightYear_*.jpg`
- 视频：`/sdcard/Movies/LightYear/LightYear_*.mp4`，H.264 / 1080×1920 / 30fps / AAC 立体声
- 从 MP4 抽帧确认滤镜**和调色**都已编码进视频（见上方对比图）

**模拟器的限制**：变焦不支持（`zoomRange` 返回 1.0–1.0），所以变焦滑块显示"此设备不支持变焦"。
真机上可用。

## 六、这台机器上构建的坑

**Gradle 的单元测试任务在这台机器上跑不起来**，报 `ClassNotFoundException`，
但类文件明明存在。根因是项目路径含中文（`E:\灰度地带\`）+ JVM 的
`sun.jnu.encoding = GBK`：classpath 里的中文绝对路径被错误解码。

`run_tests.py` 绕过这个问题：**cd 到 `app/build` 用相对路径**（纯 ASCII），
依赖 jar 从 Gradle 缓存按需取，AndroidX 的 AAR 现场解压出 `classes.jar`。
它跑的是同一批编译产物和同一个 JUnit，所以绿灯是可信的。当前 **80 项全过**。

其他踩过的坑：

| 现象 | 原因 | 处理 |
|---|---|---|
| `Your project path contains non-ASCII` | AGP 硬检查 | `android.overridePathCheck=true` |
| TLS handshake 中断 | 直连 Maven Central 不稳 | 换阿里云镜像 + 重试参数 |
| transforms 缓存移动失败 | Windows 文件锁 | 删 `~/.gradle/caches/8.9/transforms` |
| 启动弹「选择应用」 | 手机开了应用分身（User 900），同一包注册两次 | `pm disable-user --user 900` |
| 快门卡住不动 | 12MP JPEG 全图逐像素解码，约 1.6 秒且在主线程 | 降采样到 1200px + 移到 `Dispatchers.Default`，降到 63 ms |
| **L\* = 115** | 用三通道伪造光谱曲线算 Lab，白点归一化错误 | 改走 线性RGB→XYZ→Lab，不伪造光谱 |
| 切 GC 后读数不合理 | 旧白参考在新物理域里被复用 | 切 GC 即清标定 |
| 照片存不进相册 | 自己 insert 一行占位，CameraX 内部又 insert 一次，撞车抛异常 | 改用 `OutputStream`，只插一次 |
| `-P` 参数被吞 | PowerShell 把 `-Pandroid.overridePathCheck=true` 当成任务名 | 写进 `gradle.properties`，命令行不带 |
| `Unable to delete directory 'app\build\outputs\apk\release'` | **adb server 一直持有 `app-release.apk`** | `adb kill-server` 后再构建；或先 `push` 到 `/data/local/tmp` 再 `pm install` |
| 变焦永远 1.0× 起 | 滑杆下界写死 `1f`，且 `requireLensFacing` 会在 filter 前就收窄候选 | 下界改用 `zoomState.minZoomRatio`；朝向过滤自己写在 `addCameraFilter` 里 |
| `UnsupportedOperationException` in CameraFilter | filter 回调里读 `info.zoomState.value`，那时相机未绑定 | 改读 Camera2 静态特性 `CONTROL_ZOOM_RATIO_RANGE` |
| 曝光显示 `+1.3 EV` 实际只补了 0.7 | EV 步长写死 `/3.0`，这台设备是 1/10 | 读 `CONTROL_AE_COMPENSATION_STEP` |
| vivo 装不上（只有"取消安装"） | 超级守护管控非官方来源应用 | 手动点蓝色"授权本次安装"；授权一次后 `pm install` 就通了 |

## 七、已知边界

- **手机是色差计，不是光谱仪。** 三个宽通道决定不了光谱，App 只报颜色，
  不报光谱曲线。要光谱得上多光谱模组（桌面版支持）。
- **没有绝对精度。** 手机相机没有出厂光谱标定，读数只在同一光源、同一次白平衡下
  互相可比。跨光源比较需要重新标定。**内置的 1–3 ΔE 容差是行业常见值，不是本机的
  实测能力**——要用作质检，必须先用色卡和参考仪器做对照。
- **准星与实际采样区没有严格对齐。** Preview 和 ImageCapture 是两条独立流，
  没有共享 `ViewPort`，所以屏幕上框住的范围和照片里采样的 18% 中心区可能差一点。
  用带边界色块可以看出来。修法是 `UseCaseGroup` + `ViewPort`，还没做。
- **AE/AWB 锁是软锁。** 通过 `Camera2Interop` 设置，个别厂商 ROM 可能忽略；
  锁失败时右上角会显示「曝光未锁」，不会假装成功。
- **只有后置/前置两路**，没有 RAW、没有手电筒常亮。
- **0–1 区间（超广角）变焦拿不到 —— 这是平台限制，不是没做。** 详见下节。
- **横屏自适应未完成**，界面锁竖屏。详见下节。
- **历史只在内存**，进程被杀即丢失。
- **变焦/曝光补偿依赖设备能力**，不支持的设备上滑块会显示提示而不是假装可用。

### 前置倒置的根因：SensorTexture 矩阵不含传感器朝向

现象：切到前置，画面是倒的。

根因不是镜像方向，而是**少转了一次**。`SurfaceTexture.getTransformMatrix()`
只描述"缓冲区→纹理"的映射，它**不知道传感器是怎么装的**。实测这台机器：

```
后置 sensor.orientation = 90
前置 sensor.orientation = 270     ← 差 180°
```

前置比后置多 180°，只靠 SurfaceTexture 矩阵就会画成倒的。

修法：用 CameraX 的 `SurfaceRequest.TransformationInfo.getRotationDegrees()`。
它已经算好"还要再转多少度才正"，直接喂给 shader 的 `u_rotate` 即可：

```kotlin
request.setTransformationInfoListener(glExecutor) { info ->
    rotationDegrees = info.rotationDegrees      // 后置 90°，前置 270°
}
```

**注意不要自己把 `Surface.ROTATION_*` 换算成度数塞进去。** 实测横屏时
`rotationDegrees` 返回 180°，而不是直觉上的 270° —— 因为 CameraX 已经把
一部分旋转编进了 `getTransformMatrix()` 的矩阵里。自己再算一遍就是转两次，
画面会既转 90° 又上下颠倒。

### 为什么横屏自适应没做成

试过三条路，都不成立，所以这一版诚实锁竖屏：

| 尝试 | 结果 |
|---|---|
| UI 随传感器重排（控制条移到右侧） | 取景器是自绘 SurfaceView，EGL 缓冲尺寸跟着 Compose 布局走，而 `SurfaceProcessor` 输出朝向固定。两者不一致，GL 必须补旋转，但矩阵里已含一份 → 转两遍 |
| 只在 shader 里补旋转 | 同上，横屏画面转 90° 且上下颠倒 |
| 只改 `targetRotation` 让 CameraX 重配 | 更糟：CameraX 把输出缓冲改成横向（实测 1163×2067 → 882×496），而我们锁了竖屏，画面被压成一条横带，连竖屏都回不去 |

要真正支持，得把 GL 管线改成"按 `targetRotation` 重建输入 SurfaceTexture"，
而不是在 shader 里补转 —— 那是结构性改动，需要重写 `GlFilterProcessor` 的
输入生命周期。`OrientationTracker.kt` 已经写好并保留，下一轮直接接上即可。

**横构图仍然能拍**：手持横拍时照片按传感器方向写入 EXIF，相册里会正确
横过来显示。缺的只是"取景界面本身横过来"。

### 超广角：代码已实现，但这台设备不暴露

`findUltraWidePhysicalId()` 走的是 `CameraCharacteristics.getPhysicalCameraIds()`
＋按焦距挑最短的那颗（主摄 4.84mm / 超广角 1.66mm），然后通过
`availablePhysicalCameraRequestKeys` 里的 `PHYSICAL_CAMERA_ID` 切换。

但实测这台 vivo：

```
cameraIdList            → ["0", "1"]          （只有两个逻辑相机）
getPhysicalCameraIds()  → 空集                 （不暴露物理子相机）
dumpsys physicalIds     → [3 0]               （metadata 里有，API 拿不到）
zoomRatioRange          → 0.6..10.0 确实存在于某个 device
```

也就是说硬件有超广角、系统相机能用，但 HAL 没有通过标准 API 暴露给三方应用。
代码在支持的机型上会直接生效（面板里的开关会自动可用），这台机器上会显示
"此设备未暴露超广角物理相机"。

## 八、下一步

1. **横屏自适应（结构性）**：把 `GlFilterProcessor` 的输入改成按
   `targetRotation` 重建 SurfaceTexture，而不是在 shader 里补转。
   `OrientationTracker` 已就绪。
2. **准星与采样区对齐**：`UseCaseGroup` + `ViewPort` 共享视口，这是目前最明显的
   精度隐患——框住的区域和被采样的区域必须严格是同一块。
3. **3D LUT 滤镜**：现在的 8 种观感是解析式（数学公式）。真实相机 App 用
   33³ 或 17³ 的 LUT 文件（InstaShot 和 Google Photos 都是），
   `LiveFilters.sampleLut` 已经写好三线性插值，接文件即可。
4. **RAW/DNG**：走 RAW 能绕开厂商 ISP 的调色，这是唯一能接近「真实测量」的路。
5. **多点平均**：现在只取中心一块；改成 3×3 多点取中位数能进一步降噪。
6. **标定卡**：印一张已知色块卡，App 自动识别并做多点校正，能部分补偿
   手机相机非理想的光谱响应。
7. **网格线 / 定时器**：参考应用都有的取景辅助，下一步补齐。
