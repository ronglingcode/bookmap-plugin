# Rename implementation symbols without changing calculations or removing callbacks.
-dontshrink
-dontoptimize
-allowaccessmodification
-dontusemixedcaseclassnames
-repackageclasses bmtrader.internal

# Bookmap discovers this entry point through runtime annotations and a no-arg constructor.
# Interface overrides retain their names automatically through the Bookmap library JARs.
-keep public class com.bookmap.plugin.rong.RongPlugin {
    public <init>();
}
-keepattributes Exceptions,InnerClasses,EnclosingMethod,Signature,*Annotation*

# These dependencies use reflection and service discovery; only our implementation is renamed.
-keep class com.bookmap.plugin.shaded.** { *; }
-keep class org.slf4j.** { *; }
-keep class com.google.errorprone.annotations.** { *; }

# EnumMap/valueOf and Java serialization can invoke these members reflectively.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
