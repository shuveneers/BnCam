Required native runtime libraries for arm64-v8a:

1. libopencv_java4.so
   Already present in this folder.

2. libc++_shared.so
   Required by libopencv_java4.so. The crash log showed that this file was missing from the APK.
   Copy the arm64-v8a libc++_shared.so from the same Android NDK / OpenCV packaging setup used by the project into this folder:

   app/src/main/jniLibs/arm64-v8a/libc++_shared.so

Do not mix ABIs. The libc++_shared.so must match arm64-v8a.
