# 拾页 · 平板离线阅读器

一个给安卓平板用的离线电子书阅读器，支持 EPUB / MOBI / PDF。没有 Gradle、没有第三方依赖、没有网络请求：一个 `Activity` 加一个 WebView，用 `aapt2 + javac + d8 + zipalign + apksigner` 直接打包，APK 约 41 KB。

> A dependency-free offline ebook reader for Android tablets (EPUB / MOBI / PDF). One Activity, one WebView, no Gradle, no network. The whole APK is about 41 KB. Books are never bundled: a local Python script prepares your own library and pushes it to the device over adb.

**本仓库只有源码，不含任何电子书。** 书库由本机脚本从你自己的书生成，再推到平板的应用专属目录。

## 它是什么样的

- 书架：封面网格，可搜索书名/作者，可按「正在阅读 / 电子书 / PDF」筛选，按书名或最近阅读排序。
- EPUB / MOBI：**真正的分页排版**。正文用 CSS 多栏切成整页，纵向不可滚动，只能左右翻页，和纸书一致。章末自动进入下一章，目录可跳章节并支持搜索。
- PDF：原版页面渲染，左右滑动或按钮翻页，双指缩放、双击放大、放大后拖动平移，可跳页。
- 排版：字号、纸张颜色（素纸 / 暖页 / 夜读）、单栏 / 双栏可调（屏幕够宽时双栏生效），改动后按比例回到原来的位置并对齐页边界。
- 全屏阅读：系统状态栏与导航栏默认隐藏，上下菜单栏也隐藏，点击正文即可显隐。
- 应用内导入：书架上「＋ 导入书籍」调系统文件选择器，可多选，支持 EPUB 和 PDF；长按书卡可移除。
- 阅读进度自动保存，杀进程或重装后继续。全程离线，飞行模式照常读。

## 结构

```
app/
  AndroidManifest.xml
  res/                       自适应图标、备份规则
  assets/                    index.html + app.js + style.css，阅读器界面
  src/com/pekinlcc/reader/   MainActivity.java，宿主与原生 PDF 阅读器
scripts/
  build_app.sh               直接调 aapt2 / javac / d8 / zipalign / apksigner
  install_app.sh             adb 安装并同步书库
  prepare_library.py         把 EPUB/MOBI/PDF 整理成阅读副本 + catalog.json
  device_test.py             通过 WebView DevTools 协议驱动真机
tests/
```

WebView 加载 `https://reader.local/index.html`，`shouldInterceptRequest` 从 `getExternalFilesDir(null)/library` 提供书籍文件。章节正文渲染在 `sandbox="allow-same-origin"` 的 iframe 里 —— 没有 `allow-scripts`，所以电子书自带的脚本不会执行。PDF 不走 WebView：JS 通过 `Reader.openPdf(id, title)` 通知原生侧，`MainActivity` 切到 `PdfRenderer` 支撑的自绘 `View`。

书籍的 MIME 类型不只看扩展名 —— 很多 EPUB 的正文文件根本没有扩展名，所以未知后缀会按内容嗅探（XML / HTML / SVG 与 PNG / JPEG / GIF / WebP 幻数）。

## 构建

需要 JDK 17 和 Android SDK Platform 35 + Build Tools 35.0.0，放在 `tools/android-sdk/`（或自行修改 `scripts/build_app.sh` 里的路径）。

```sh
bash scripts/build_app.sh      # 产出 dist/Shiye.apk
bash scripts/install_app.sh    # adb 安装并同步书库
```

签名口令来自环境变量 `KS_PASS`，或本地文件 `tools/keystore.pass`（`tools/` 不入库）。首次构建会用这个口令在 `tools/reader.keystore` 生成一个签名密钥。

**请自行备份 keystore**：丢了以后重新构建会换签名，覆盖安装将失败（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），只能卸载重装并重新导入书库。

`install_app.sh` 优先用 `tools/platform-tools/adb`，找不到就退回 `PATH` 里的 `adb`，也可以用 `ADB=/path/to/adb` 指定。

## 导入自己的书

把 EPUB / MOBI / PDF 放进项目下的 `电子书汇总/`，然后：

```sh
python3 scripts/prepare_library.py
```

脚本会解包 EPUB、用 [mobi](https://pypi.org/project/mobi/) 转换 MOBI（旧格式按 `<mbp:pagebreak>` 拆章，需要 Beautiful Soup）、复制 PDF，并生成 `library/catalog.json`。原始文件只读不改。之后 `install_app.sh` 会把 `library/` 推到 `/sdcard/Android/data/com.pekinlcc.reader/files/library/`。

书库不打包进 APK。覆盖更新保留书库与进度，卸载则一并删除。

## 调试与测试

`scripts/device_test.py` 通过 WebView DevTools 协议驱动真机，`tests/` 下是配套的设备端脚本。使用前注意三点：

- 需要 `pip install websocket-client`。
- DevTools 只在可调试构建里开启（`MainActivity` 按 `FLAG_DEBUGGABLE` 判断），而清单里写的是 `android:debuggable="false"`。要用它得先临时改清单再重新构建。
- 端口转发由 `tests/restart_epub.py` 建立（`adb forward tcp:9223 localabstract:webview_devtools_remote_<pid>`），单独跑 `device_test.py` 需要自己先转发。

`tests/` 是作者自用的真机脚本，不是可复现的测试套件：它假定 `tools/platform-tools/adb` 存在，并会写入 `verification/`，这两处都不在仓库里。`ReaderInstrument` 需要用 `-e ids <id1>,<id2>` 传入 PDF 书籍 id。

## 已知范围

PDF 是原版页面阅读，没有 OCR、全文搜索或文字重排；夜读模式只影响 PDF 的界面外框，页面本身按原样渲染。EPUB / MOBI 支持目录搜索，暂无正文全文搜索。书库导入目前只能通过电脑脚本完成。

## 开发记录

### 1.7 · 真正的分页

此前正文是一个长滚动文档，"翻页"只是按屏高滚动，因此上下滑动会变成自由滚动 —— 不该是阅读器的行为。现在改为 CSS 多栏分页：

- `body` 固定为一屏高（`border-box` + `height:100%`），`column-width` 按视口算，`column-fill:auto`，`html` 设 `overflow:hidden`。纵向可滚动量恒为 0，强制 `scrollTo(0,N)` 也会回到 0。
- 翻页 = 横向滚动整数个视口宽。栏距 `gap` 同时充当左右页边距，左边距用 `margin-left:gap/2`，因此单栏和双栏下每屏步进都正好等于视口宽。
- 阅读位置、字号变化、单双栏切换、显隐菜单栏、旋转与折叠，全部按比例回到原位并对齐页边界。

### 1.5 / 1.6 · 应用内导入

把 `prepare_library.py` 的 EPUB 解析移植到 Java（`Importer.java`），配合系统文件选择器实现应用内导入，两条路径写出的 `catalog.json` 结构一致。拿 30 本真实 EPUB 逐字段对比过电脑管线：章节路径、目录条目、封面、书名、作者全部一致。

导入处理的是用户随手挑的文件，因此做了针对性防护：解压做路径逃逸校验与条目数/体积上限；XML 拒绝带内部实体声明的 DOCTYPE（安卓的 `DocumentBuilderFactory` 不支持常用的 XXE 关闭开关，那几行其实会静默失效）；书名与错误信息回传页面前做转义。三类恶意样本实测均被拦截。



近期一轮完整代码复查修掉的问题，按影响排序：

**阅读**

- 文件名没有扩展名的 EPUB 正文被当成二进制流下发，WebView 拒绝渲染，整本书翻过封面就是空白。改为按内容嗅探类型。
- 章节加载后有一次延迟 250 毫秒的位置校正，原本无条件执行，会覆盖读者在这期间的翻页并把错误位置写进书签。改为只在没有真实滑动时校正。
- 切换栏宽只重排版面，不重新定位也不刷新页码，会让「下一页」一直是灰的。
- 某章加载失败后，加载提示会永久停在失败文案，污染整个会话。

**交互**

- 返回键无法退出应用；退出 PDF 时会误弹「已在书架」提示。
- Android 13+ 走 `OnBackInvokedDispatcher`，`onBackPressed` 不再被调用，返回键会直接结束 Activity 而不是回书架。改为注册 `OnBackInvokedCallback`。
- 渲染大页时按返回会卡住主线程（关闭流程要等渲染线程让锁）。改为把渲染器交给后台线程关闭。
- 重复打开同一本 PDF 会泄漏 `PdfRenderer` 与文件描述符；页数为 0、渲染内存不足会直接崩溃。

**外观**

- 夜读模式下 PDF 界面与系统状态栏仍是浅色；导航栏白底白字；被选中的主题按钮浅底浅字，对比度 1.07:1。
- 桌面图标不是自适应图标，被系统塞进小白圆里。
- `applyChrome` 在 `onCreate` 里跑在 `setContentView` 之前，此时 decor view 尚未创建，`getInsetsController()` 必定空指针 —— 每次启动都闪退。改为挂到 `onAttachedToWindow`。

**数据与脚本**

- `allowBackup` 没有规则，默认包含外部文件目录，几百 MiB 的书库撑爆 25 MiB 备份配额后整个应用被静默排除，连阅读进度都备份不到。
- 一本坏书会中断整轮书库整理，`catalog.json` 永远写不出来，已解压目录留在磁盘上。改为逐本容错，失败的书沿用上一版条目。
- 大写扩展名（`.PDF` / `.EPUB`）能通过筛选却匹配不到任何分支，最后落进 MOBI 分支的整目录复制。

## 许可

MIT，见 [LICENSE](LICENSE)。仓库不包含任何书籍内容；请只导入你有权阅读的文件。
