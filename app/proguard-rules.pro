# Lifecycle 2.8.x bridges its LocalLifecycleOwner to Compose UI 1.6.x via
# reflection. Keep the reflected getter and its declaring class in release
# builds; otherwise R8 can remove/rename them and the app crashes at startup.
-keep class androidx.compose.ui.platform.AndroidCompositionLocals_androidKt {
    public static androidx.compose.runtime.ProvidableCompositionLocal getLocalLifecycleOwner();
}
