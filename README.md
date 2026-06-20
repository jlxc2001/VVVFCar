# Miku VVVF Fighter HUD V9

车速绑定声浪模拟 Demo。V9 在 V8 的 MainApp Hook + 平滑数据源基础上，重做了 UI：默认主界面只保留战斗 HUD 风格车速显示，所有调试项隐藏到长按设置面板里。

## V9 更新

- 默认主界面只显示车速：大号 `km/h` 数字 + 战斗 HUD 线框/准星/扫描线。
- 长按任意主界面区域打开设置。
- 调试滑条、自动测试、Hook 开关、风格切换、音量、静音、UDP/ADB 指令全部移动到设置里。
- 新增 Service → Activity 状态广播，主界面显示的是音频线程内部平滑后的车速，而不是 500ms 原始 Hook 跳变值。
- 保留 V8 的 MainApp Hook 数据源和 500ms 低频轮询约束。

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

## ADB 调试

```bash
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_SPEED --ef speed 45
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style SAMPLE_VVVF_0_140
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style AIRCRAFT_TURBINE
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_HOOK --ez enabled false
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_HOOK --ez enabled true
adb shell am broadcast -a com.jlxc.mikuvvvf.STOP
```

## UDP 调试

端口：`47230`

```text
SPEED 45
STYLE SAMPLE_VVVF_0_140
STYLE AIRCRAFT_TURBINE
HOOK 0
HOOK 1
POLL 500
PING
```

## 声音模式

```text
SAMPLE_VVVF_0_140   真实采样 VVVF，0→140km/h
SIEMENS_GZ_GTO      广东地铁西门子 GTO 风格
GTO                 通用老式 GTO
IGBT                通用现代 IGBT
AIRCRAFT_TURBINE    飞机/涡扇引擎
POP_BANG_TURBO      偏时点火/回火
NATURAL_ASPIRATED   自然吸气
ROTARY              转子发动机
SUPERCHARGED_V8     机械增压 V8
```
