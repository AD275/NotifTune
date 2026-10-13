# 保持 Xposed 入口类名不被混淆（release 暂未开启混淆，此为保险）
-keep class com.notiftune.HookEntry { *; }
