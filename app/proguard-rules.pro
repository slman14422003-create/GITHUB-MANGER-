# Keep readable stack traces in crash reports (mapping.txt is uploaded by the workflow)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Hardening: remove all debug/verbose logging and flatten package names
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
-repackageclasses ''
-allowaccessmodification
