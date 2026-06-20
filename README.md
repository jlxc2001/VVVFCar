# Miku VVVF / Engine Sound Demo V6

车速绑定声浪模拟 Demo。

## V6 重点变化

1. **新增真实 VVVF 采样模式**
   - 默认模式：`SAMPLE_VVVF_0_140`
   - 内置样本：`app/src/main/res/raw/vvvf_0_140.wav`
   - 样本含义：用户提供的 VVVF 平滑加速录音，约 `123.54s`，`48kHz`，双声道，速度范围按 `0 → 140 km/h` 映射。
   - App 会根据当前车速映射到录音中的对应位置，再用短粒度循环/交叉淡入淡出维持匀速，避免纯算法合成的“假电子音”。

2. **自动测试变为 0→140→0，且更慢**
   - 每 90ms 增减 0.10 km/h，接近样本原始爬升节奏，方便听 VVVF 换段。

3. **保留原有模式**
   - `SIEMENS_GZ_GTO`
   - `GTO`
   - `IGBT`
   - `AIRCRAFT_TURBINE`
   - `POP_BANG_TURBO`
   - `NATURAL_ASPIRATED`
   - `ROTARY`
   - `SUPERCHARGED_V8`

4. **发动机声浪方向说明**
   - V5 已经把发动机模式从 VVVF 合成器里拆出来，改用曲轴/点火/排气脉冲/虚拟挡位/RPM 的模型。
   - V6 先接入真实 VVVF 采样；发动机下一步建议继续参考 `engine-sim` 的思路重构：节点化发动机脚本、点火顺序、缸体几何、排气管脉冲、进气/排气卷积。

## UDP 指令

端口：`47230`

```text
SPEED 45
STYLE SAMPLE_VVVF_0_140
STYLE SIEMENS_GZ_GTO
STYLE AIRCRAFT_TURBINE
STATE 60 3200 0.65
PING
STOP
```

## ADB 调试

```bash
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_SPEED --ef speed 45
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style SAMPLE_VVVF_0_140
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style SIEMENS_GZ_GTO
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style AIRCRAFT_TURBINE
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style SUPERCHARGED_V8
adb shell am broadcast -a com.jlxc.mikuvvvf.STOP
```

## GitHub Actions 打包

上传整个项目到 GitHub 后，进入 Actions 手动运行 `Android Build`，会输出 Debug APK。

## 注意

- 真实采样模式会把 WAV 载入内存，样本约 23MB。大部分 Android 10+ 车机可以承受；如果低内存设备启动失败，下一版可以把 WAV 改成分段流式读取或压缩成 OGG 后用双播放器交叉淡入淡出。
- 当前 VVVF 采样是“车速 → 录音位置”的音色模拟，不是还原真实牵引逆变器控制算法。
