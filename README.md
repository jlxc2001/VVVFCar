# Miku VVVF Sound Demo

车速绑定 VVVF 声浪模拟 Demo。

包名：`com.jlxc.mikuvvvf`  
默认 UDP 端口：`47230`

## V3 更新：广东地铁西门子 GTO 预设

这一版新增了独立预设：

- `SIEMENS_GZ_GTO`：广东/广州地铁西门子 GTO-VVVF 风格。
- 目标听感参考广州地铁 1 号线 A1 / Adtranz-Siemens GTO-VVVF 的“地铁味”。
- 不再只做普通分段升频，而是加入：
  - 低速 GTO 起步敲击；
  - 5.5 / 18 / 32 / 52 / 78 km/h 多段切换；
  - 中低速阶梯式音阶；
  - 中速二阶段/三阶段锁相啸叫；
  - 高速弱磁细高频；
  - 轻微轨道/车厢底噪，让声音更像地铁而不是单纯电子哨声。

保留旧预设：

- `GTO`：通用粗糙老电车。
- `IGBT`：通用顺滑现代电车。

## 功能

- Android `AudioTrack` 实时合成声音，不依赖 mp3 循环。
- 主界面支持手动速度滑条。
- 支持自动 `0 → 100 → 0 km/h` 测试。
- 支持 `SIEMENS_GZ_GTO` / `GTO` / `IGBT` 三种风格。
- 支持音量、静音。
- 支持 UDP 局域网车速输入。
- 支持 ADB Broadcast 模拟车速。
- 前台服务播放，避免后台被系统杀掉。

## UDP 指令

发送 UTF-8 文本到车机 / 手机 IP 的 `47230` 端口：

```text
SPEED 45.0
```

切换广东地铁西门子预设：

```text
STYLE SIEMENS_GZ_GTO
```

其他指令：

```text
STYLE GTO
STYLE IGBT
VOLUME 0.65
MUTE 1
UNMUTE
PING
STOP
```

`STATE` 也能收，但当前只使用第一个车速字段：

```text
STATE 37.5 1800 1
```

## ADB 调试

```bash
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_SPEED --ef speed 45
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_SPEED --ef speed 0
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style SIEMENS_GZ_GTO
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style GTO
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style IGBT
adb shell am broadcast -a com.jlxc.mikuvvvf.STOP
```

## Windows 电脑测试 UDP

PowerShell 可用下面命令发 UDP：

```powershell
$udp = New-Object System.Net.Sockets.UdpClient
$bytes = [Text.Encoding]::UTF8.GetBytes("SPEED 45")
$udp.Send($bytes, $bytes.Length, "车机IP", 47230)
$udp.Close()
```

切换到广东地铁西门子预设：

```powershell
$udp = New-Object System.Net.Sockets.UdpClient
$bytes = [Text.Encoding]::UTF8.GetBytes("STYLE SIEMENS_GZ_GTO")
$udp.Send($bytes, $bytes.Length, "车机IP", 47230)
$udp.Close()
```

模拟从 0 到 100：

```powershell
$udp = New-Object System.Net.Sockets.UdpClient
for ($i=0; $i -le 100; $i++) {
  $msg = "SPEED $i"
  $bytes = [Text.Encoding]::UTF8.GetBytes($msg)
  $udp.Send($bytes, $bytes.Length, "车机IP", 47230)
  Start-Sleep -Milliseconds 80
}
$udp.Close()
```

## 接入 MikuCarLauncher 的建议

在车机 Launcher 获取到车速后，以 10~20Hz 频率发送 UDP 即可：

```text
SPEED 当前车速
```

例如：

```text
SPEED 37.5
```

第一版不需要转速、油门、刹车。App 内部会根据速度变化自动判断加速 / 减速 / 匀速。

## 编译

GitHub Actions 已包含在：

```text
.github/workflows/android-build.yml
```

手动构建：

```bash
gradle assembleDebug
```

产物位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```
