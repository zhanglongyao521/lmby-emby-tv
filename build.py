# -*- coding: utf-8 -*-
"""Emby TV 客户端构建脚本：aapt2 -> javac -> d8 -> 打包 -> zipalign -> apksigner"""
import os, sys, shutil, subprocess, zipfile, glob

PROJ = os.path.dirname(os.path.abspath(__file__))

# Android SDK / JDK 所在目录：优先读环境变量 ANDROID_BUILD_HOME，
# 其次用本机原来的位置，最后回落到工程内的 build-tools/。
ROOT = os.environ.get("ANDROID_BUILD_HOME") or r"C:\Users\超级计算机\AppData\Local\embytv-build"
JAVA = os.path.join(ROOT, "jdk-17.0.2", "bin")
BT = os.path.join(ROOT, "android-14")

# 第三方依赖统一放在工程目录的 deps/ 下（不入库，见 README）
DEPS = os.environ.get("EMBY_DEPS") or os.path.join(PROJ, "deps")
ANDROID_JAR = os.path.join(DEPS, "android.jar")
VLC = os.path.join(DEPS, "vlc")
MEDIA3 = os.path.join(DEPS, "media3", "jars")
ZXING = os.path.join(DEPS, "zxing", "core-3.5.3.jar")

APP = os.path.join(PROJ, "app")
# 编译中间产物和 APK 的输出目录：可用环境变量 EMBY_OUT_DIR 指定
OUTDIR = os.environ.get("EMBY_OUT_DIR") or r"D:\embytv-build"
BUILD = os.path.join(OUTDIR, "work")
OUT = os.path.join(OUTDIR, "EmbyTV.apk")

def run(cmd, cwd=None, env=None):
    print("> " + " ".join(str(c) for c in cmd[:6]))
    e = os.environ.copy()
    e["JAVA_HOME"] = os.path.join(ROOT, "jdk-17.0.2")
    e["PATH"] = JAVA + ";" + e.get("PATH", "")
    if env:
        e.update(env)
    p = subprocess.run(cmd, cwd=cwd, env=e, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = p.stdout.decode("utf-8", "replace")
    if p.returncode != 0:
        print(out[-6000:])
        raise SystemExit("FAILED: %s" % " ".join(str(c) for c in cmd))
    return out

def main():
    if os.path.isdir(BUILD):
        shutil.rmtree(BUILD, ignore_errors=True)
    os.makedirs(BUILD, exist_ok=True)
    aapt2 = os.path.join(BT, "aapt2.exe")

    # 1. 资源编译
    run([aapt2, "compile", "--dir", os.path.join(APP, "res"), "-o", os.path.join(BUILD, "res.zip")])

    # 2. 资源链接 -> base.apk
    run([aapt2, "link", "-o", os.path.join(BUILD, "base.apk"),
         "-I", ANDROID_JAR,
         "--manifest", os.path.join(APP, "AndroidManifest.xml"),
         "-R", os.path.join(BUILD, "res.zip"),
         "--auto-add-overlay",
         "--min-sdk-version", "17", "--target-sdk-version", "28"])

    # 3. javac
    os.makedirs(os.path.join(BUILD, "classes"))
    srcs = []
    for r, d, f in os.walk(os.path.join(APP, "src")):
        for n in f:
            if n.endswith(".java"):
                srcs.append(os.path.join(r, n))
    if not srcs:
        raise SystemExit("no java sources")
    cp = ANDROID_JAR + os.pathsep + os.path.join(VLC, "classes.jar")
    if os.path.exists(ZXING):
        cp = cp + os.pathsep + ZXING
    for j in glob.glob(os.path.join(MEDIA3, "*.jar")):
        cp = cp + os.pathsep + j
    out = run([os.path.join(JAVA, "javac.exe"), "-nowarn", "-source", "8", "-target", "8",
               "-bootclasspath", ANDROID_JAR, "-classpath", cp,
               "-encoding", "utf-8", "-d", os.path.join(BUILD, "classes")] + srcs)
    warn = [l for l in out.splitlines() if "错误" in l or "error" in l.lower()]

    # 4. d8 -> classes.dex
    os.makedirs(os.path.join(BUILD, "dex"))
    cjar = os.path.join(BUILD, "classes.jar")
    run([os.path.join(JAVA, "jar.exe"), "cf", cjar, "-C", os.path.join(BUILD, "classes"), "."])
    m3 = glob.glob(os.path.join(MEDIA3, "*.jar"))
    extra = [ZXING] if os.path.exists(ZXING) else []
    # build-tools 34 自带的 d8 (R8 8.2) 处理 media3 1.7.1 的匿名类会崩，改用独立 R8
    R8 = os.environ.get("EMBY_R8") or os.path.join(OUTDIR, "tools", "r8-9.5.32.jar")
    run([os.path.join(JAVA, "java.exe"), "-cp", R8, "com.android.tools.r8.D8",
         "--release", "--min-api", "17",
         "--lib", ANDROID_JAR,
         "--classpath", os.path.join(VLC, "classes.jar"),
         "--output", os.path.join(BUILD, "dex"), cjar, os.path.join(VLC, "classes.jar")] + m3 + extra)

    # 5. 组装 apk（dex + so + assets + 资源）
    apk = os.path.join(BUILD, "unsigned.apk")
    with zipfile.ZipFile(os.path.join(BUILD, "base.apk")) as base, zipfile.ZipFile(apk, "w", zipfile.ZIP_DEFLATED) as z:
        for info in base.infolist():
            z.writestr(info, base.read(info.filename))
        # dex
        z.write(os.path.join(BUILD, "dex", "classes.dex"), "classes.dex", zipfile.ZIP_DEFLATED)
        # native libs (stored, zipalign 会对齐)
        natives = glob.glob(os.path.join(VLC, "jni", "armeabi-v7a", "*.so"))
        natives += glob.glob(os.path.join(DEPS, "media3", "jni", "armeabi-v7a", "*.so"))
        for so in natives:
            zi = zipfile.ZipInfo("lib/armeabi-v7a/" + os.path.basename(so))
            zi.compress_type = zipfile.ZIP_STORED
            with open(so, "rb") as f:
                z.writestr(zi, f.read())
        # vlc assets
        assets = os.path.join(VLC, "assets")
        for r, d, f in os.walk(assets):
            for n in f:
                full = os.path.join(r, n)
                rel = os.path.relpath(full, assets).replace("\\", "/")
                z.write(full, "assets/" + rel, zipfile.ZIP_DEFLATED)
        # 自有 assets（内置背景图 bg/bg1..bg10.jpg 等）
        own = os.path.join(PROJ, "assets")
        if os.path.isdir(own):
            for r, d, f in os.walk(own):
                for n in f:
                    full = os.path.join(r, n)
                    rel = os.path.relpath(full, own).replace("\\", "/")
                    z.write(full, "assets/" + rel, zipfile.ZIP_DEFLATED)

    # 6. zipalign
    run([os.path.join(BT, "zipalign.exe"), "-p", "-f", "4", apk, os.path.join(BUILD, "aligned.apk")])

    # 7. 签名
    ks = os.path.join(PROJ, "debug.keystore")
    if not os.path.exists(ks):
        run([os.path.join(JAVA, "keytool.exe"), "-genkeypair", "-keystore", ks,
             "-alias", "emby", "-storepass", "android", "-keypass", "android",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-dname", "CN=EmbyTV,OU=Dev,O=Dev,L=WX,S=JS,C=CN"])
    if os.path.exists(OUT):
        os.remove(OUT)
    run([os.path.join(BT, "apksigner.bat"), "sign", "--ks", ks, "--ks-pass", "pass:android",
         "--key-pass", "pass:android", "--ks-key-alias", "emby",
         "--out", OUT, os.path.join(BUILD, "aligned.apk")])
    run([os.path.join(BT, "apksigner.bat"), "verify", OUT])
    print("BUILD OK: %s (%.1f MB)" % (OUT, os.path.getsize(OUT) / 1048576.0))

if __name__ == "__main__":
    main()
