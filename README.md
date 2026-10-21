# 红果上划助手


**[下载安装包](https://github.com/RippleSoul/hongguo-swipe/releases/latest)**

## 怎么用

1. 下载并安装 Releases 中的 APK。
2. 打开助手，点击 **打开**；按跳转的系统页面找到 **红果上划助手**，开启无障碍服务并允许。
3. 返回助手，按钮显示 **已开启**，再回到红果看视频即可，横竖屏共用一个版本。
4. 要停止时，回到助手点击 **已开启**。

如果无法自动跳转，手动到 **系统设置 → 无障碍 → 已下载的应用／已安装的服务 → 红果上划助手** 开启；不同品牌菜单名称可能不同。


支持 Android 7.0 及以上。已在 OPPO Android 16、红果 7.3.9.32 使用；其他手机和广告样式可能需要适配，无法识别的广告不会操作。

## 从源码构建

准备 Python 3、JDK 17、Android SDK Platform 33 和 Build Tools 34.0.0；将 `JAVA_HOME`、`ANDROID_SDK_ROOT` 指向对应安装目录，然后运行：

```sh
python3 build.py
```

输出 `红果上划助手.apk`。脚本会在本机生成签名文件，请自行保留，以便后续覆盖安装；自行构建的安装包签名与 Releases 版本不同。

策略检查：

```sh
mkdir -p build/test-classes
javac -encoding UTF-8 -d build/test-classes src/cn/local/hongguoswipe/SkipGate.java tests/SkipGateTest.java
java -cp build/test-classes SkipGateTest
```

## 许可证

[MIT](LICENSE)
