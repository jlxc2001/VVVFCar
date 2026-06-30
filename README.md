# Miku VVVF Fighter HUD V12

车速 / 发动机转速绑定声浪模拟 Demo。V12 在 V11 基础上重点继续优化真实 VVVF 音频文件的播放逻辑，目标是减少“循环声”。

## V12 更新

- 优化 `SAMPLE_VVVF_0_140` 的采样播放方式。
- 不再使用 V11 的固定速度段 loop bank，所以不会再固定重复同一段 0.6~1 秒循环。
- 改成更接近游戏音频引擎的“非固定循环采样器”：
  - 速度映射到 0→140km/h 真实录音中的目标位置；
  - 加速/减速时使用连续 tape-follow 播放头追随目标位置；
  - 匀速时使用多粒度随机 grain cloud 做 sustain；
  - 每个 grain 的起点、长度、播放速率、增益都有轻微随机化；
  - 多个 grain 用 Hann 窗重叠，减少循环接缝和固定重复感。
- 保留 V10/V11 的全部功能：`CUSTOM_VVVF`、设置持久化、后台静音、RPM 绑定、MainApp Hook、500ms 平滑。

> 注意：只有一条“0→140km/h 加速录音”时，真正完全没有循环感很难。V12 已经尽量用随机粒度采样来打散循环；如果还想更真实，最终最好准备多组稳定 loop 文件，例如 0/10/20/30...140km/h 各一条稳定巡航音，再像赛车游戏一样做多层 crossfade。

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
SAMPLE_VVVF_0_140   真实采样 VVVF，0→140km/h，V12 非固定循环 grain/tape 混合播放
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
