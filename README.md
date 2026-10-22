# 红果助手

安卓红果广告自动上划工具，附带睡眠关闭功能。首页一个大按钮，无悬浮窗，无联网、录音或截图权限。

**[下载安装包](https://github.com/RippleSoul/hongguo-swipe/releases/latest)**

## 怎么用

1. 安装 APK，打开助手，点击 **打开**。
2. 在系统无障碍页面找到 **红果助手**，开启服务并允许。
3. 返回首页显示 **已开启**，再去红果看视频；横竖屏都可以。
4. 再点 **已开启** 可停止自动上划。

无法自动跳转时，手动到 **系统设置 → 无障碍 → 已下载的应用／已安装的服务 → 红果助手** 开启。各品牌的菜单名称可能不同。

## 睡眠

左上角菜单有 **首页** 和 **睡眠**。进入睡眠页，选择一种方式并开启：

- **倒计时**：设置分、秒，到时退出红果播放页面并锁屏。可直接输入数字，或选 15／30／60 分钟快捷时长。
- **自动检测**：红果前台时，根据手机感受到的持续少动推测入睡，达到设置时长后执行，不要求关灯。移动手机或可识别的点击会重新计时；离开红果、进入画中画或息屏会暂停并清空检测进度。

自动检测只是手机体动推测，**静躺看剧也可能误触发**，不能判断人是否真的睡着。缺少运动传感器时会提示使用倒计时。检测时长为 5–120 分钟，默认 15 分钟；倒计时最多 12 小时。

睡眠页可 **更新设置** 或 **关闭睡眠**。睡眠结束也会停止自动上划；睡眠与首页上划开关可分别控制。倒计时在切换应用后继续，到时仍会锁屏。

睡眠锁屏需要 **Android 9 或以上**，并保持无障碍服务开启。系统终止服务会影响执行；重连时恢复尚未到期的任务，重启手机或重连时已过期的任务不补执行。普通应用无法强杀其他应用进程，本工具发送暂停指令、退出播放页面再锁屏；部分播放器的后台播放行为可能需要适配。

## 自动上划

仅在红果前台检测广告标记，每 400 毫秒检查一次，上划前再次确认。可读倒计时会等待结束；不可读时有限重试，不按固定视频时长切换正常剧集。切到其他应用、画中画或息屏后停止广告轮询。

自动上划支持 Android 7.0 及以上。已使用 OPPO Android 16、红果 7.3.9.32 验证；其他手机和广告样式可能需要适配。

## 从源码构建

准备 Python 3、JDK 17、Android SDK Platform 33 和 Build Tools 34.0.0，设置 `JAVA_HOME`、`ANDROID_SDK_ROOT`，然后运行：

```sh
python3 build.py
```

输出 `红果助手.apk`。请保留本机生成的签名文件以便覆盖更新；自行构建的签名与 Releases 版本不同。

策略检查：

```sh
mkdir -p build/test-classes
javac -encoding UTF-8 -d build/test-classes src/cn/local/hongguoswipe/SkipGate.java src/cn/local/hongguoswipe/SleepDetector.java tests/SkipGateTest.java tests/SleepDetectorTest.java
java -cp build/test-classes SkipGateTest
java -cp build/test-classes SleepDetectorTest
```

## 许可证

[MIT](LICENSE)
