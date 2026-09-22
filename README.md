# CardHome 卡片桌面

给 Android 平板、学习机、电视盒子用的第三方桌面（Launcher）。桌面主体是一排半透明卡片，每张卡片对应一个应用：图标在左、名称在右，卡片本身有一套逐层合成的渲染管线，配色跟着系统动态色走。

- 包名：`com.cardhome.app`
- 目标设备：Android 13 平板（横竖屏跟随系统旋转）
- 技术栈：Java + AndroidX AppCompat + Material Components（Material Design 3）
- 主题：`Theme.Material3.DynamicColors.DayNight`（Android 12+ 跟随系统动态取色）
- 许可：GPL-3.0

## 功能

- 顶部时钟与问候语，右下是卡片区，卡片按屏宽自动分页、整页翻页
- 卡片分层渲染：底色层、深色模式层、强模糊层、图标层、渐变模糊层依次合成，最后叠上文字层
- 两种配色：动态取色（系统主题色）与图标取色（按图标主色）
- 深浅色模式：跟随系统 / 深色 / 浅色
- 桌面长按空白处弹出 MD3 菜单，含「桌面设置」与「刷新应用列表」
- 桌面长按卡片直接拖拽排序，拖动过程中顺序实时变化，松手即写盘
- 手柄 / 扫描笔当键盘连接时，按 A、B、C、D 可直接打开当前页第 1~4 张卡片（纯按键响应，不加任何界面元素）
- 独立的设置页：MaterialToolbar + MaterialCardView + MaterialButtonToggleGroup + MaterialCheckBox，应用列表可勾选、长按拖动排序、改名（MaterialAlertDialogBuilder + filled TextInputLayout）
- 图标处理：灰度重映射后按主题色重新着色，图标原有的白色保留为纯白、轮廓保持清晰
- 崩溃兜底：启动异常时显示可复制的崩溃报告，并支持安全模式

按 A、B、C、D 打开卡片用的是 `dispatchKeyEvent`，只吃这四个按键事件，不往界面上加任何东西，也不改布局。

## 构建

需要 JDK 17 与 Android SDK（compileSdk 36、minSdk 29、targetSdk 34）。工程没有 Gradle Wrapper，直接用本机 Gradle：

```bash
gradle assembleDebug     # 调试包：app/build/outputs/apk/debug/app-debug.apk
gradle assembleRelease   # 发行包：app/build/outputs/apk/release/app-release.apk
```

首次构建需要联网拉取 AndroidX 与 Material 依赖（`google()` / `mavenCentral()`）。

## 发行签名

`keystore/cardhome-release.jks` 是工程自带的测试签名密钥（别名 `cardhome`，store/key 口令都是 `cardhome`），
提交在仓库里是为了让本机和 CI 打出来的 release 包签名一致、可以互相覆盖安装。
它不适合用于正式分发；要换成自己的密钥，在根目录放 `keystore.properties`：

```properties
storeFile=my-release.jks
storePassword=******
keyAlias=mykey
keyPassword=******
```

CI 上则通过 `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` 四个 Secrets 提供，未配置时自动使用仓库自带的密钥。

注意：debug 包与 release 包用的是不同密钥，互相覆盖安装需要先卸载。

## 持续集成

`.github/workflows/release.yml`：

- push 到 main、提交 PR、手动触发：构建 debug 与 release 两个 APK，上传为构建产物
- 推 `v*` 标签：额外把两个 APK 附到对应的 GitHub Release 上

## 设计说明

**卡片渲染**：卡片不是把一张位图贴上去，而是每一层都对「它下面已经合成出来的全部内容」做一次处理。底色层是约 40% 不透明的纯色；深色模式层是 20% 的黑灰；第三层把当前内容整体做强模糊；第四层放图标，先裁到图标自身的内容边界（自适应图标四周有大片透明留白），再按卡片高度铺满；第五层把含图标的内容再模糊一次，按横向渐变换回去，用来消掉图标层与非图标区域之间的硬边界；文字层最后叠上去，保持清晰。

**背景模糊**：卡片本身是半透明的，透过它看到的就是真实壁纸，所以背景模糊交给系统做——Android 12+ 的窗口级背景模糊（`FLAG_BLUR_BEHIND`），由系统实时处理窗口背后的内容，而不是自己读壁纸位图再铺一层。

**界面**：设置页直接用 Material 库的 Material Design 3 组件与主题，颜色角色、形状、字号都来自主题。

**桌面菜单**：Material 库没有公开的 MD3 菜单组件，所以菜单容器用 Material 库的 `MaterialShapeDrawable`（4dp 圆角、2dp 阴影、`colorSurfaceContainer` 填充）加菜单项自己拼，颜色取自主题的 MD3 颜色角色，并按桌面自己的深浅色设置切换主题。

**数据**：应用显示状态、顺序、自定义名称都存在 `SharedPreferences("cardhome")` 里，桌面与设置页共用同一份读写逻辑。

## 已知限制

- 背景模糊需要设备支持（Android 12+ 且厂商没有关闭背景模糊）；逐视图的背景模糊属于系统内部接口，在部分设备上不可用。
- release 包没有开 R8 混淆与资源压缩，体积偏大（debug 约 13 MB，release 约 11 MB）。
- 目前只在 Android 13 平板上实测过。

## 许可

本项目以 GNU General Public License v3.0 发布，详见 [LICENSE](LICENSE)。