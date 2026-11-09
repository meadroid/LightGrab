# 无障碍服务：系统通过 manifest 类名反射创建，必须保持原名
-keep class com.light.grab.GrabService { *; }

# MainActivity 里的 AppOpsCheck 走反射调用系统方法，系统方法不会被混淆，无需额外 keep。

# 保留无障碍服务在 XML/Manifest 中被引用的完整性
-keepclassmembers class com.light.grab.GrabService {
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent);
    public void onServiceConnected();
    public void onInterrupt();
}
