# Miku VVVF Fighter HUD V11

车速 / 发动机转速绑定声浪模拟 Demo。V11 在 V10 自定义 VVVF、设置持久化、后台静音和 RPM 绑定基础上，重点优化了真实 VVVF 音频文件的播放逻辑。

## V11 更新

- 优化 `SAMPLE_VVVF_0_140` 的采样播放方式。
- 参考游戏里的引擎声实现：不再用一个播放窗口不断追着速度硬移动，而是把 0→140km/h 的真实 VVVF 音频映射成多个速度循环段。
- 当前速度会在相邻两个速度段之间等功率交叉淡入淡出，类似游戏中 RPM loop crossfade。
- 加入第二层低音量长循环，用来打散明显的循环感。
- Hook 车速 500ms 更新时，采样播放头不会再频繁硬跳，减少“嘟嘟嘟”和颗粒感。
- 保留 V10 的 `CUSTOM_VVVF` 自定义模式、声音类型持久保存、后台静音、RPM 绑定。

## V10 功能保留

- `CUSTOM_VVVF` 自定义 VVVF 模式。
- 默认自定义参数按用户提供截图：
  - `0.0 - 34.5 km/h`：Asynchronous，固定 `1050 Hz - 1050 Hz`
  - `34.5 - 38.0 km/h`：Synchronous，`9 Pulses`
  - `38.0 - 160.0 km/h`：Synchronous，`Wide 3 Pulses`
- 设置界面可调自定义 VVVF 的：A段结束速度、9P 切换速度、最高映射速度、异步固定载波、同步脉冲数、Wide 脉冲数。
- 声音类型会持久保存：选 A 就一直保持 A，选 B 就一直保持 B；重新进入软件不会再变回默认。
- “软件不在前台时关闭声音”开关，默认开启。
- “根据发动机转速绑定声音”开关。
- 默认主界面只显示战斗 HUD 风格车速，长按屏幕进入设置。

## 默认数据源

默认优先绑定：

```text
com.ts.MainUI/com.ts.can.carinfo.CarInfoService
```

读取：

```text
ICarInfoService.requestCarBaseInfo()
base[2] = 车速 km/h
base[3] = 发动机转速 rpm
```

轮询最低 500ms。声音引擎内部会对速度/RPM 做连续插值，避免声音卡顿。

## 主界面操作

```text
长按屏幕：打开设置
```

主界面无按钮，不显示调试信息。

## 声音模式

```text
SAMPLE_VVVF_0_140   真实采样 VVVF，0→140km/h，V11 游戏式速度段循环/交叉淡入淡出
CUSTOM_VVVF         自定义 VVVF，默认按 1050Hz / 9P / Wide 3P
SIEMENS_GZ_GTO      广东地铁西门子 GTO 风格
GTO                 通用老式 GTO
IGBT                通用现代 IGBT
AIRCRAFT_TURBINE    飞机/涡扇引擎
POP_BANG_TURBO      偏时点火/回火
NATURAL_ASPIRATED   自然吸气
ROTARY              转子发动机
SUPERCHARGED_V8     机械增压 V8
```

## UDP 调试

端口：`47230`

```text
SPEED 45
STATE 45 2300 0.5
STYLE SAMPLE_VVVF_0_140
STYLE CUSTOM_VVVF
CUSTOM 34.5 38 160 1050 9 3
RPMBIND 1
RPMBIND 0
BGMUTE 1
BGMUTE 0
HOOK 0
HOOK 1
POLL 500
PING
```

## ADB 调试

```bash
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_SPEED --ef speed 45
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style SAMPLE_VVVF_0_140
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style CUSTOM_VVVF
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_CUSTOM_VVVF --ef cut1 34.5 --ef cut2 38 --ef max 160 --ef async 1050 --ei sync 9 --ei wide 3
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_RPM_BIND --ez enabled true
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_BACKGROUND_MUTE --ez enabled true
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_HOOK --ez enabled false
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_HOOK --ez enabled true
adb shell am broadcast -a com.jlxc.mikuvvvf.STOP
```
