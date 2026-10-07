# 文件上传、扫码方向与标签切换修复计划

日期：2026-10-07。状态：已实施代码修复并补充回归测试；完整构建、测试执行与设备复现受环境限制，详见第 8 节。第 1 节保留修复前的排查基线。

## 1. 范围与结论

“多窗口切换按钮”暂按浏览器标签数量按钮、标签列表及标签切换理解；Android 系统分屏作为回归场景。

| 问题 | 已获得的证据 | 判断 |
| --- | --- | --- |
| 文件无法上传 | `BrowserPresenter.onFileChooserResult()` 将结果交给 currentTab；启动端没有异常兜底；销毁 Tab 时没有结束文件回调 | 已确认回调归属和清理缺陷；尚不能将用户遇到的所有上传失败归因于此 |
| 扫码强制横屏 | `BrowserActivity.showQrScanner()` 仅设置 setOrientationLocked(false)，Manifest 未覆盖 CaptureActivity 方向 | 与扫码库官方方向配置要求不符，是首要修复点；构建后检查合并 Manifest |
| 多窗口按钮闪退 | `TabPager.selectTab()` 强制解引用；底部面板维护两份显示状态；缩略图缩放公式错误、裁剪无边界保护 | 缩略图算法缺陷已确认；按钮闪退尚未复现，不能宣称已定位唯一根因 |
| 扫码其他问题 | URL scheme 使用区分大小写比较；取消与错误均只依赖 contents 为 null；无显式重复启动保护 | 大写 HTTP/HTTPS 会被当作文本；权限、相机错误、重复启动和恢复行为需要验证 |

已有保护：TabClick/TabClose 检查标签是否存在；扫码只自动打开具有 host 的 HTTP/HTTPS 内容，其他内容供查看复制；文本显示上限为 4096 字符。保留这些行为，避免重复修复或放宽协议限制。

## 2. 第一阶段：建立可复现基线

1. 确认 Android SDK、项目要求的 JDK、Gradle 和设备可用，记录 Android/WebView 版本、普通或无痕模式、标签布局。
2. 准备本地上传测试页：单文件、多文件、image/*、PDF、多个 MIME、空 accept、扩展名 accept、capture 属性。展示 input.files 数量和名称，并向测试端点实际提交以验证读取结果。
3. 分别观察：未出现选择器、立即取消、选完文件网页无反应、真正提交失败。记录文件选择器类型、结果码、请求标识和标签 ID，不记录文件内容。
4. 扫码记录进入前后方向、系统旋转开关、首次授权及取消路径。
5. 多窗口分开复现“展开列表”“点击标签”“关闭标签”“连续快速操作”，抓取异常堆栈，确认是 UI、缩略图加载还是 WebView 崩溃。

## 3. 文件上传修复（P1）

涉及：TabWebChromeClient.kt、TabModel.kt、TabAdapter.kt、BrowserPresenter.kt、BrowserActivity.kt、BrowserContract.kt；必要时新增文件选择请求协调器。

1. 为每次请求保存来源 Tab 和请求标识；结果只交回发起请求的回调，不再查询 currentTab。标签 ID 可能复用，应校验请求身份，不能只保存整数 ID。
2. 每个浏览器 Activity 同时最多保留一个系统选择请求。明确重复请求的取消/拒绝策略，避免旧选择器结果误交给新回调。
3. 统一完成和取消入口：先清除挂起状态，再调用 ValueCallback；成功、取消、启动失败、发起标签关闭、Activity 销毁都最多完成一次。打开选择器导致 onPause 时不能取消请求。
4. 在创建 Intent 和启动选择器的边界处理实际可发生的异常，例如 ActivityNotFoundException、SecurityException；返回取消结果并显示可理解的错误。不能让异常终止整个浏览器事件处理链。
5. 用测试结果核实 createIntent/parseResult 的兼容性，再决定是否增加 MIME 规范化或自建 Intent。多选必须覆盖 ClipData；取消不能返回旧 URI。不把未经复现的多选问题描述成已有缺陷。
6. 检查外部文件提供器 URI 的可读性及临时授权，不申请宽泛存储权限来绕过选择器问题。拍照 capture 若确定属于故障范围，再增加 FileProvider 输出 URI 和取消清理；否则作为明确的功能边界记录。
7. 对进程死亡采取取消并允许重新发起的恢复策略，不尝试持久化 WebView 回调对象。

验收：单选、多选实际提交成功；切换标签后仍返回原网页；关闭原标签无崩溃和串页；取消/启动失败后能再次上传；普通和无痕模式均通过。

## 4. 扫码方向与相关问题修复（P1/P2）

涉及：AndroidManifest.xml、BrowserActivity.kt、BrowserPresenter.kt，以及必要的扫码结果解析辅助类、字符串资源。

1. 显式覆盖 CaptureActivity 的 screenOrientation，默认采用尊重用户旋转设置的 user 方向策略，保留 setOrientationLocked(false)。若需要更独立的扫码行为，可改为项目专用 CaptureActivity 子类。
2. 检查最终合并 Manifest，确认覆盖生效。竖屏进入时不主动变横屏，允许系统设置支持的方向切换；退出后浏览器方向正确。
3. 保留扫码库已有的权限和相机管理；先验证首次拒绝、永久拒绝、无摄像头、相机占用、后台恢复，再补足应用层错误提示，避免重复申请权限。
4. 区分用户取消和相机/权限错误；为重复点击增加在途状态控制，结果或取消时恢复，明确 Activity 重建后的状态恢复规则。
5. 将扫码内容分类提取为可测试逻辑：trim、HTTP/HTTPS scheme 忽略大小写并规范化；保留非空 host 要求。javascript/file/content/intent 等仍仅按文本展示，不自动执行。
6. 测试空文本、中文、换行、长文本、异常 URL；保留显示长度限制，复制完整文本的行为应明确。合法 URL 仍走现有加载链路并核实站点限制生效。

验收：旋转开关开/关均无意外横屏；权限拒绝与相机错误可退出、可重试；取消不新建标签；成功只处理一次；大小写 HTTP/HTTPS 正确识别，其他协议不执行。

## 5. 多窗口/标签切换排查与修复（P1/P2）

涉及：TopCropTransformation.kt、TabPager.kt、TabsRepository.kt、BrowserPresenter.kt、BottomTabs.kt、DrawerTabs.kt、DesktopTabs.kt。

1. 优先修复可独立证明的缩略图算法：现有高度表达式 targetWidth × input.height / targetWidth 基本等于原高度，未按原宽度等比缩放。改为覆盖目标矩形的等比缩放，顶部对齐裁剪，保证宽高为正且裁剪范围不超出缩放图。
2. 覆盖超宽图、超高图、目标比原图大、1 像素和原始尺寸请求。图片加载失败显示占位，不阻断列表。区分 Coil 捕获的加载错误与真正未捕获的崩溃。
3. 检查 Repository、Presenter、Pager 在创建、删除、恢复和选择中的一致性。无效请求在移除当前视图之前拒绝；不要仅将 !! 改成 ?. 后让模型与界面不一致。选中状态须与真正显示的 WebView 一致。
4. 对底部面板连续开关做定向复现；若确认竞态，将 openTabs 作为唯一目标状态，通过有明确 key 的副作用驱动显示/隐藏，消除组合阶段直接更改显示状态导致的相互覆盖。
5. 检查三种标签布局的滚动目标在删除、恢复后是否仍有效；无有效目标时跳过滚动。复查 AndroidView 容器在布局切换时的父子关系，按证据修复重复挂载。
6. 不用吞掉所有异常的方式掩盖闪退；每个修复都应对应复现步骤或可证明的不变量违反。

验收：三种布局分别连续展开/关闭 50 次，交替切换/关闭至少 20 次；覆盖最后一个标签、恢复标签、后台恢复和横竖屏切换。没有崩溃、空白容器、错误选中状态或失效面板。

## 6. 验证与交付

| 层次 | 必须覆盖 |
| --- | --- |
| 单元测试 | 上传请求归属、取消及重复结果；扫码 URL 分类；缩略图几何边界 |
| Android/Robolectric 或仪器测试 | 位图缩放裁剪、选择器结果解析、关闭 Tab 时清理；按现有测试能力选择环境 |
| 设备回归 | 项目最低 API 28、一个中间版本、目标平台可用设备；普通/无痕；三种标签布局；旋转和分屏 |
| 构建检查 | 相应构建变体的单元测试、lint、debug APK 构建；检查合并 Manifest |

执行顺序：基线复现 → 上传请求生命周期 → 扫码方向 → 已确认的缩略图缺陷 → 其余按复现结果修复 → 完整回归。建议按上述模块分提交，便于审查和回退。

最终交付应包含：代码改动、每项问题的证据与根因、已执行测试及结果、APK（构建可用时）、未复现项和剩余环境限制。不得把静态排查等同于真机验证，也不得将尚未复现的闪退报告为已修复。

当前环境初步检查：PATH 中未找到 java 或 adb，尚未执行构建和设备测试；实施时先定位可用工具链。

## 7. 外部依据

- ZXing 官方方向配置说明要求同时修改 Manifest 和 setOrientationLocked(false)：https://github.com/journeyapps/zxing-android-embedded/blob/master/README.md#changing-the-orientation
- Android FileChooserParams 文档说明 createIntent/parseResult 的使用及选择器启动失败处理：https://developer.android.com/reference/android/webkit/WebChromeClient.FileChooserParams

## 8. 已实施修改与验证记录

### 已实施

1. 两个浏览器 Activity 从 singleInstance 改为 singleTask，保留各自任务 affinity 和无痕独立进程。补充排查发现：API 28 的 ActivityStarter 会给 singleInstance 发起的子页面添加 NEW_TASK，导致等待结果的父页面提前收到取消；这既影响文件选择，也影响扫码。依据：https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/services/core/java/com/android/server/am/ActivityStarter.java 和 https://developer.android.com/reference/android/app/Activity#startActivityForResult(android.content.Intent,int,android.os.Bundle)。较新 Android 版本的具体表现仍需设备回归。
2. 新增 FileUploadRequest，直接绑定原始 WebView 回调，去掉“结果传给 currentTab”的路径。支持单 URI 和多选 ClipData；取消和结果最多完成一次，完成前先清除回调。
3. 上传请求通过缓冲 Channel 发送，由每个标签持续监听，不随当前标签切换停止监听。现有请求未完成时拒绝新的同页请求；Activity 保留一个在途选择器，即使原标签关闭，也要等旧选择器返回后才允许下一次启动，避免旧结果串入新请求。
4. 选择器启动失败有提示并取消原回调；关闭标签和销毁浏览器时清理挂起回调。Activity 重建后旧 WebView 的上传取消，迟到结果被忽略，恢复后可重新发起。
5. Manifest 覆盖 CaptureActivity 为 user 方向，保留不锁定方向，跟随用户旋转偏好。增加扫码启动失败提示与重复启动防护，保留扫码库自身的权限及相机错误对话框。上传与扫码在同一 Activity 内互斥启动。
6. 扫码 URL scheme 规范化，修复大小写 HTTP/HTTPS；空内容忽略，带内部空白或控制字符的内容按文本处理，其他协议仍不执行。普通内容仍可查看复制。
7. 缩略图按覆盖目标矩形等比缩放，顶部对齐、水平居中裁剪，处理未指定维度及放大情况，并更改缓存键。只回收新建的中间位图，不回收输入或输出。
8. TabPager 在移除当前页面前检查目标是否存在，迁移旧父容器后再挂载。Repository 只在显示成功后更新选中标签；失效选择请求保持现有页面，重复删除不抛异常。
9. 底部标签面板用带目标状态 key 的副作用控制挂载和隐藏，取消过时的隐藏动作，交由 ModalBottomSheet 在布局锚点就绪后显示。三种布局滚动前检查索引。
10. 普通浏览器全屏方向/系统栏更新移入 LaunchedEffect，避免每次重组重复操作。无痕退出直接调用 Activity.finishAndRemoveTask() 结束当前任务，避免新增子页面后 topActivity 变化导致 first() 抛异常，也兼容最低 API 28。

### 补充的验证材料

- FileUploadRequestTest：5 项，覆盖来源绑定、重复结果、多选、取消、迟到结果和重入回调。
- ScannedUrlTest：2 项，覆盖大小写、路径保留、文本、空内容和非网页协议。
- TopCropTransformationTest：3 项，覆盖宽高极端情况、放大、顶部裁剪和未指定尺寸。
- TabPagerTest：2 项，覆盖失效目标保持当前页面和已有父容器迁移。
- docs/upload-regression.html：单选、多选、MIME、扩展名、取消和 capture 输入测试页，读取实际字节但不会传输文件。可用 `python3 -m http.server 8000 --directory docs` 提供网页，再在设备上访问可达的开发机地址。实际服务端提交仍需使用可信测试端点单独验证。

### 已执行与未执行

- `git diff --check`：通过。
- Python XML 解析：Manifest 和英文、简体中文、繁体中文字符串资源通过，无重复 string 名称。
- 源 Manifest 配置检查：两个 Activity 均为 singleTask，扫码方向为 user，扫码 Activity 不导出。未检查构建后的合并 Manifest。
- 尝试执行 `./gradlew :app:testLightningPlusDebugUnitTest :app:lintLightningPlusDebug :app:assembleLightningPlusDebug --offline`：Gradle wrapper 启动前失败，提示 JAVA_HOME 未设置且 PATH 中不存在 java。不是测试或编译通过。
- 未找到 Android SDK/adb，未运行 APK、相机或系统选择器。新增的 12 项测试尚未执行，尚不能保证编译通过。

### 真机回归重点

先验证两个原始问题：文件选择器返回后 input.files 和字节读取正确、扫码竖屏进入且识别结果能够返回。随后验证普通/无痕的任务切换、外部链接、设置页返回和退出；检查 singleTask 带来的任务栈行为变化。三种标签布局快速开关和切换、相机授权/拒绝/占用、旋转和进程重建按第 6 节继续执行。

多窗口按钮闪退仍未在设备上复现：当前修复覆盖了可证明的缩略图越界和失效标签/父容器风险，不把这些改动等同于已验证消除了用户可能遇到的全部闪退。

### 首次云端构建及修正

GitHub Actions 37553615276（修复提交 e49d055b）完成了代码和测试编译及 APK 打包，执行 89 项单元测试，87 项通过，2 项 TabPagerTest 失败。完整 XML 报告确认：测试中手动创建的 WebView 缺少 WebViewFactory 设置的 CompositeTouchListener tag，长按处理器注册时发生空指针。修正测试工厂初始化，使其遵循生产 WebView 的初始化约定；保留失效标签和父容器迁移断言，未跳过测试或放宽断言。等待后续完整云端构建结果。

GitHub Actions 37554503062（测试修正提交 6f1fdc8a）中 89 项单元测试全部通过；随后 lint 指出 TaskInfo.taskId 需要 API 29，不兼容最低 API 28。移除无痕退出时的任务列表查询，直接使用 API 21 起可用的 Activity.finishAndRemoveTask()。未关闭 lint 或提升最低 Android 版本。
