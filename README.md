# Miku VVVF / Engine Sound Demo

车速绑定声浪模拟 Demo。第一用途是给 MikuCarLauncher / 车机项目测试：只要外部持续发送车速，本 App 就会用 `AudioTrack` 实时合成声音。

## V4 更新

- 自动测试从旧版的快速拉升改成慢速 `0 → 120 → 0 km/h`，方便听清 VVVF 换段和发动机升挡。
- 保留原来的 VVVF 模式：
  - `SIEMENS_GZ_GTO`：广东/广州地铁西门子 GTO 风格
  - `GTO`：通用老式 GTO
  - `IGBT`：通用现代 IGBT
- 新增交通工具/发动机类声浪：
  - `AIRCRAFT_TURBINE`：飞机/涡扇推进感
  - `POP_BANG_TURBO`：改偏时点火/涡轮回火放炮
  - `NATURAL_ASPIRATED`：自然吸气/高转进气声
  - `ROTARY`：转子发动机/高转蜂鸣
  - `SUPERCHARGED_V8`：地狱猫风格/机械增压 V8
- 发动机类模式会根据车速自动模拟虚拟挡位/RPM，因此只发送 `SPEED` 也能听到升挡掉转。

> 注意：这些都是算法合成的“风格近似”，不是对真实车辆/列车录音的采样复刻。后续如果接入真实 RPM、油门、刹车，发动机类声浪会更像。

## UDP 接口

默认监听：

```text
UDP 47230
```

设置车速：

```text
SPEED 45
```

切换风格：

```text
STYLE SIEMENS_GZ_GTO
STYLE AIRCRAFT_TURBINE
STYLE POP_BANG_TURBO
STYLE NATURAL_ASPIRATED
STYLE ROTARY
STYLE SUPERCHARGED_V8
```

其他：

```text
VOLUME 0.6
MUTE 1
UNMUTE
PING
STOP
```

## ADB 调试

设置车速：

```bash
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_SPEED --ef speed 45
```

切换声音风格：

```bash
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style SIEMENS_GZ_GTO
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style AIRCRAFT_TURBINE
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style POP_BANG_TURBO
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style NATURAL_ASPIRATED
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style ROTARY
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style SUPERCHARGED_V8
```

停止：

```bash
adb shell am broadcast -a com.jlxc.mikuvvvf.STOP
```

## 接入 MikuCarLauncher

最简单只需要持续发送：

```text
SPEED 当前车速
```

例如车速 37.5km/h：

```text
SPEED 37.5
```

后续可以扩展为：

```text
STATE speed rpm throttle brake
```

目前 V4 仍然只使用 `speed`，`rpm/throttle/brake` 预留。

## 构建

这是标准 Android Gradle 项目。上传到 GitHub 后可通过 `.github/workflows/android-build.yml` 自动构建 APK。
