# Miku VVVF Sound Demo

车速绑定 VVVF 声浪模拟 Demo。  
包名：`com.jlxc.mikuvvvf`  
默认 UDP 端口：`47230`

## 功能

- Android `AudioTrack` 实时合成声音，不依赖 mp3 循环。
- 主界面支持手动速度滑条。
- 支持自动 `0 → 100 → 0 km/h` 测试。
- 支持 `GTO` / `IGBT` 两种风格。
- 支持音量、静音。
- 支持 UDP 局域网车速输入。
- 支持 ADB Broadcast 模拟车速。
- 前台服务播放，避免后台被系统杀掉。

## UDP 指令

发送 UTF-8 文本到车机 / 手机 IP 的 `47230` 端口：

```text
SPEED 45.0
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

`STATE` 也能收，但第一版只使用第一个车速字段：

```text
STATE 37.5 1800 1
```

## ADB 调试

```bash
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_SPEED --ef speed 45
adb shell am broadcast -a com.jlxc.mikuvvvf.SET_SPEED --ef speed 0
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
