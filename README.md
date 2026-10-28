# 平板功耗实验室

为小米 Pad 8 Pro 编写的本地 Android 负载测试工具。原生 Java + OpenGL ES 3.0，无广告、无需联网、无需 root，也不申请文件或设备敏感权限。

## 使用

1. 在平板上打开「平板功耗实验室」，接上充电器和 USB 功率表。
2. 选择「游戏负载」或「极限负载」，点击「开始负载测试」。档位是合成负载，不代表某个实际游戏的耗电。
3. 在功率表上读取充电输入功率。应用内可以观察电池电量、温度、系统温控状态、GPU 完成帧率和电池净功率趋势。
4. 点击「停止负载测试」，再点击「导出本次 CSV」。文件位于平板的 `下载/PowerLab/`。

| 档位 | CPU | GPU 着色迭代 | 渲染像素 | 目标帧率 | 测试亮度 |
| --- | --- | --- | --- | --- | --- |
| 轻载 | 2 线程 × 35% | 16 | 1280 × 854 | 60 | 50% |
| 游戏负载 | 4 线程 × 70% | 48 | 1920 × 1280 | 60 | 80% |
| 极限负载 | 全部逻辑核心 × 100% | 128 | 屏幕原生像素 | 屏幕支持的最高值 | 100% |

各项都可单独调节。CPU 线程数、占用比例或 GPU 迭代设为 0，可隔离 CPU / GPU 测试。CPU 占用是每个工作线程的目标占空比，实际进程占用还包含渲染、界面和系统调度开销。帧率是实际完成渲染的帧率，目标值不保证达到。刷新率是窗口请求，最终由系统决定。

GPU 始终在独立离屏缓冲区渲染，缩小预览画面不会降低设定的 GPU 渲染像素数。

## 功率读数

- **充电器输入功率**：以外接 USB 功率表为准。
- **应用显示的电池净功率**：系统电池电流 × 系统电池电压。正值表示充入电池，负值表示电池向外供电；这不是充电器输入功率，也不是应用或整机耗电功率。
- 默认遵循 Android 电池 API 的符号定义。部分固件可能相反：拔掉充电线后，待机电池净功率应为负。若相反，停止测试并点「电流方向」反转符号。CSV 同时保留原始电流和方向系数。
- 电池电压、温度和电流的更新时间由固件决定，每秒采样不代表传感器每秒更新。无法读取的数据留空，不虚构功率。
- 电量接近 100% 时，电池充电接近完成，不能据此测出低电量快充时的最大输入功率。比较不同档位时尽量保持相近的电量、温度和亮度。
- 厂商的电池拓扑或电流换算也会影响电池侧估算；没有实测校准时不额外乘上双电芯倍率。

API 依据：[BatteryManager](https://developer.android.com/reference/android/os/BatteryManager#BATTERY_PROPERTY_CURRENT_NOW)、[PowerManager 温控状态](https://developer.android.com/reference/android/os/PowerManager#THERMAL_STATUS_SEVERE)。

## 自动停止

默认测试 10 分钟，电池温度达到 43°C 时停止；可选择 45°C 上限和其他时长。系统报告严重或更高温控等级、未接电源且电量 ≤10%、应用离开前台或锁屏时也会停止。自动停止后需要手动重新开始。测试停止时清除本窗口的亮度和刷新率设置。

这些温度是本工具的停止阈值，不是厂商规定的设备温度额定值。应用遵循系统的温控与调度。

## 构建与安装

构建产物：`build/PadPowerLab.apk`。这是本地调试签名 APK。

在 macOS 上准备 JDK 17+ 后运行。首次克隆需先下载 SDK 工具：

```sh
./scripts/bootstrap.sh
./build.sh
DEVICE_SERIAL="你的设备IP:无线调试端口"
.tools/platform-tools/adb connect "$DEVICE_SERIAL"
.tools/platform-tools/adb -s "$DEVICE_SERIAL" install -r build/PadPowerLab.apk
.tools/platform-tools/adb -s "$DEVICE_SERIAL" shell am start -n com.padpowerlab/.MainActivity
```

`build.sh` 使用 Android SDK 35 的 aapt2 / d8 / apksigner，直接生成 APK，不依赖 Gradle 下载。支持 `JAVA_HOME` 指定 JDK 17+；未指定时会尝试使用 PyCharm 自带 JDK 或 macOS 注册的 JDK。SDK 默认在项目 `.tools/sdk`，也可用 `ANDROID_HOME` 和 `ANDROID_BUILD_TOOLS` 指定本地工具。`scripts/bootstrap.sh` 可下载并校验 macOS SDK 工具，平台工具使用 Google 最新下载地址。

SDK、签名密钥、构建产物、设备截图和实测数据均保留在本地，不纳入源码仓库。

同时提供标准 Android Gradle 工程，可使用 Android Studio 导入。最低 Android 10 / API 29，要求 OpenGL ES 3.0。

## 实机验证

```sh
python3 scripts/smoke_test.py "$DEVICE_SERIAL"
```

该脚本会运行短时负载、临时将系统温控状态设为严重以验证自动停止并立即恢复、检查后台停载、手动停止、CSV 导出和电流方向选项。它会短暂控制平板界面。结果、截图和抽取的 CSV 存在 `captures/`。验证脚本针对这台 8 核、3200 × 2136 的 Pad 8 Pro。

2026-10-29 已在这台平板（Android 16）通过上述验证：游戏档约 59 FPS，原生分辨率极限档约 30 FPS；CSV 为 20 列。帧率是短时测试结果，会受温度和系统状态影响，不是固定性能指标。

CSV 包含时间、耗时、事件、CPU 参数、GPU 参数、目标和实际帧率、亮度、电量、电池温度、电池毫伏、原始微安、估算瓦数、电流方向系数、接电状态、充电状态和温控等级。原始会话文件保存在应用私有目录，导出的副本保存在下载目录。
