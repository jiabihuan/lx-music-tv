# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[Project Knowledge Summary]
- Date: 2026-10-09
- Context: Discovered by Agent while overhauling TV focus border UX to match KuGou TV/Dangbei style on lx-music-tv
- Category: Troubleshooting & Debugging
- Instructions:
  - Dual-border root cause: MainActivity.applyFocusSelectorToView sets tv_focus_selector foreground on ALL focusable/clickable Views; FocusableTouchableOpacity's JS focusStyle adds another border → double frame at different positions
  - NativeID prefix `tv_no_focus_highlight_` skips foreground via isNoFocusHighlightView() ancestor-chain tag lookup; `tv_adjustable_` prefix for sliders/progress bars so JS self-draws highlight without native fallback
  - JS Focusable* components (FocusableTouchableOpacity, FocusablePressable) now use Animated.createAnimatedComponent + Animated.spring for elastic scale (1→1.06, speed=28, bounciness=5); focusStyle transform scale extracted and merged into animation target via stripScale
  - FocusBorderColor default changed from #FF69B4 pink to '' (follow theme); init/index.ts migrates old pink value once; theme.ts pushes c-primary on theme switch; setting panel has "auto" option first in list
  - Animated.createAnimatedComponent wrapper loses nativeID/focusable props in type; cast via `const Comp = AnimatedXxx as any` to pass extra TV props
  - Pressable style prop can be a function (PressableStateCallbackType); must resolve before StyleSheet.compose: `typeof style === 'function' ? style({pressed: false}) : style`
  - TouchableOpacityProps does NOT include nativeID or focusable — must declare in custom FocusableTouchableOpacityProps interface
  - npm install in this environment takes ~7min (812 packages, git deps need bob build); peak memory 1.69GB; set memory_percent=55 for background terminal
  - tsc has 17 pre-existing errors in unrelated files; bundle-android succeeds; ESLint requires @typescript-eslint/eslint-plugin installed separately (not in package.json)

[Project Knowledge Summary]
- Date: 2026-08-24
- Context: Discovered by Agent while fixing TV remote D-pad control for sliders/progress bar on lx-music-tv
- Category: Troubleshooting & Debugging
- Instructions:
  - RN 0.73's View component does NOT support onKeyDown/onKeyUp/onKeyPress props; D-pad handling for TV must be done in native MainActivity.onKeyDown interception + "tvRemoteKey" DeviceEvent forwarding to JS
  - View's `nativeID` prop maps to Android tag `com.facebook.react.R.id.view_tag_native_id`, letting native code locate JS controls via ancestor-chain tag lookup or ReactFindViewUtil.findView
  - RN's built-in Modal/Dialog only intercepts BACK/ESCAPE keys; other keys (D-pad/OK) propagate to the host Activity.onKeyDown, so Activity-level interception also covers controls inside Modals/Popups
  - react-native-navigation auto-cleans `componentIds` on pop via registerScreenPoppedListener, so Home backAction can read `commonState.componentIds.playDetail` to detect whether play detail is on top
  - Focus events (onFocus/onBlur) only exist at Touchable/Pressability level in RN 0.73, not on plain View

[Project Knowledge Summary]
- Date: 2026-08-10
- Context: Discovered by Agent while verifying build for the TV D-pad fixes on lx-music-tv
- Category: Build Methods
- Instructions:
  - Local environment has NO JDK/Android SDK; Java/native changes must be verified by GitHub Actions (gradle assembleRelease) after push, while JS changes can be locally verified with `npx react-native bundle --platform android --dev true --entry-file index.js --bundle-output index.android.bundle --assets-dest res`
  - CI only runs gradle assembleRelease (no eslint/tsc gate); eslint passes separately, and tsc has pre-existing errors in unrelated files

[Project Knowledge Summary]
- Date: 2026-08-24
- Context: Discovered by Agent while removing the white focus frame around full-screen MV playback on lx-music-tv (user complained twice it was not fully removed)
- Category: Troubleshooting & Debugging
- Instructions:
  - MainActivity 会遍历整棵视图树，对所有可聚焦/可点击 View 调用 `view.setForeground(tv_focus_selector)`（白色 3dp 边框前景），这是"画面四周白框"的根本来源；仅靠 RN focusStyle 或禁 view 自身焦点高亮无法消除，因为 foreground 是另一层
  - 根治方式：给不需要系统焦点前景的 View 设置 `tv_no_focus_highlight_` 前缀的 nativeID，MainActivity 的 `applyFocusSelectorToView` 通过 `isNoFocusHighlightView()` 沿祖先链查 tag（`com.facebook.react.R.id.view_tag_native_id`）命中后跳过 setForeground
  - RN 版 KTV 全屏播放页（src/screens/Home/Views/Ktv/index.tsx）的全屏透明焦点锚点 TouchableOpacity 是焦点白框的主要承载者：菜单选歌后 focus 落回锚点即出现整屏白框，已给锚点加 `tv_no_focus_highlight_fs_anchor` nativeID 跳过
  - 双重保障：MainActivity.applyFocusSelectorToView 在 `fullscreenKeyCapture=true`（KTV 全屏且无菜单/控制条）时全局直接 return 不画任何焦点框，保证播放画面四周绝无白框；菜单/控制条打开时（keyCaptureOn=false）恢复焦点框，菜单行/控制条按钮/居中按钮的聚焦样式全部保留，不得再给这些元素加 tv_no_focus_highlight_ nativeID
  - 排行榜长按 OK 全部播放：MainActivity.onKeyLongPress 已把长按（longPress=true）转发 JS "tvRemoteKey" 事件；Leaderboard/MusicList.tsx 监听该事件（keyCode 23/66 且 longPress），直接复用 listAction.handlePlay(boardId)（内含 getListDetailAll 全量拉取），无需另写拉取逻辑

[Project Knowledge Summary]
- Date: 2026-08-24
- Context: Discovered by Agent while fixing global lag (all screens stutter after MV section) and the full-screen pause bug on lx-music-tv
- Category: Troubleshooting & Debugging
- Instructions:
  - RN @ReactMethod 默认在「原生模块单线程」上同步执行；若在方法体内直接做网络请求（如 CfssSpider.singerAvatar 最长 30s 超时），该线程被占满会导致 App 内所有 native 调用（播放器、存储、图片加载）全部排队，表现为 MV 板块后全界面卡顿、返回失灵。根治：网络调用提交到独立线程池（newFixedThreadPool(4)），native 线程立即返回，Promise 在线程池回调中 resolve
  - 全屏播放暂停/恢复按键错乱根因：KTV 全屏控制条按钮用 opacity 0 隐藏但仍是可聚焦 View；暂停恢复播放时居中按钮卸载、焦点可能抢入控制条按钮，触发其 onFocus 把 showControls 置 true → keyCaptureOn 变 false → 原生停止拦截 OK，后续暂停/播放按键全部错乱。修复：控制条按钮加 `focusable={showControls}`（隐藏时不可聚焦），恢复后焦点必然回落到全屏锚点
  - KTV 全屏遥控器键位约定（MainActivity.isFullscreenCaptureKey 拦截转发 JS tvRemoteKey）：OK/Enter=暂停/播放，DPAD_UP=上一曲，DPAD_DOWN=下一曲，DPAD_LEFT/RIGHT=快退/快进 10s（Video ref.seek），MENU=呼出歌曲菜单；暂停时左上角展示视频分辨率（Video onLoad 的 e.naturalSize.width×height）

[Project Knowledge Summary]
- Date: 2026-08-21
- Context: Discovered by Agent while reverse-engineering wexguard OLLVM-obfuscated shell (wexguard_v7.so in spider.jar) to decrypt .guard file
- Category: Troubleshooting & Debugging
- Instructions:
  - wexguard_v7.so data segment (0xbd9c, size 0x7ec) self-decrypts via `.datadiv_decode10638385061521549500` (Thumb entry 0x1bbd, odd addr = Thumb flag); emu_start MUST use odd Thumb address (0x1bbd), even address (0x1bbc) causes unicorn to misdecode push.w and write wrong SP
  - After unicorn emulation of datadiv_decode, dump mem 0xbd9c..0xc588 reveals JNI strings: full loader flow is getLoader -> read assets/wexshinidie.guard -> decrypt -> DexClassLoader; references classes Init/InitOrigin/ProxyOrigin/DexNative (all inside shell jar, not host deps)
  - Real host-class deps confirmed: only `com.github.catvod.crawler.Spider` (provided) plus shell-internal Init; ProxyOrigin/InitOrigin/DexNative belong to shell jar itself
  - .mytext (0x5960-0x95a4) holds the OLLVM-flattened getLoader/decrypt code; fully emulating the JNI call chain (GetMethodID on android classes etc.) is high-effort
  - guard file is strong-encrypted (entropy 7.99, no dex magic), offline decrypt infeasible without running the so
  - unicorn 2.1.4 installed globally (import unicorn), capstone 5.0.9 (CS_ARCH_ARM, CS_MODE_THUMB); map so ELF LOAD segments at vaddr==offset into mem 0x0..0x20000, stack at 0x30000000 with 0x10000 size

