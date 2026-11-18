# 内置的 Xposed 模块（拼多多驿站直通）
#
# LSPosed 是「读 assets/xposed_init 里的类名字符串 → 反射加载」这条路径进来的，
# R8 一改名就再也找不到入口，模块静默失效 —— 而且失效时不会报错，只是点了没反应。
-keep class com.bhu.campus.hook.** { *; }
-keepnames class com.bhu.campus.hook.**

# xposed api 是 compileOnly，运行时由 LSPosed 注入，打包时类根本不存在
-dontwarn de.robv.android.xposed.**

# 无障碍服务、Activity 这些由 manifest 注册的组件 AGP 会自动 keep，
# 这里只兜一层，免得以后换 AGP 时被优化掉。
-keep class com.bhu.campus.DrinkBalanceService { *; }
