@echo off
rem Собирает native/fflaunch.c в app/src/main/jniLibs/arm64-v8a/libffmpeg.so и libffprobe.so
rem (имена жёстко заданы youtubedl-android: --ffmpeg-location <nativeLibraryDir>/libffmpeg.so)
set "NDK=C:\Users\Xotabl4\AppData\Local\Android\Sdk\ndk\27.0.12077973"
set "CC=%NDK%\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android24-clang.cmd"
cd /d "C:\Users\Xotabl4\Desktop\apk\ytdlp_android"
call "%CC%" -O2 -Wall -fPIE -pie -o "app\src\main\jniLibs\arm64-v8a\libffmpeg.so" native\fflaunch.c
call "%CC%" -O2 -Wall -fPIE -pie -o "app\src\main\jniLibs\arm64-v8a\libffprobe.so" native\fflaunch.c
echo SHIM_EXIT=%ERRORLEVEL%
