/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vogar.commands;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import vogar.Classpath;
import vogar.Md5Cache;
import vogar.Strings;

/**
 * Android SDK commands such as adb, aapt and dx.
 */
public class AndroidSdk {

    private static final Logger logger = Logger.getLogger(AndroidSdk.class.getName());
    private static final Md5Cache DEX_CACHE = new Md5Cache("dex");

    private static final Comparator<File> ORDER_BY_NAME = new Comparator<File>() {
        public int compare(File a, File b) {
            return a.getName().compareTo(b.getName());
        }
    };

    private final File androidClasses;

    private AndroidSdk(File androidClasses) {
        this.androidClasses = androidClasses;
    }

    public static AndroidSdk getFromPath() {
        List<String> path = new Command("which", "adb").execute();
        if (path.isEmpty()) {
            throw new RuntimeException("Adb not found");
        }
        File adb = new File(path.get(0));
        String parentFileName = adb.getParentFile().getName();

        /*
         * We probably get adb from either a copy of the Android SDK or a copy
         * of the Android source code.
         *
         * Android SDK:
         *  <sdk>/tools/adb
         *  <sdk>/platforms/android-?/android.jar
         *
         * Android build tree:
         *  <source>/out/host/linux-x86/bin/adb
         *  <source>/out/target/common/obj/JAVA_LIBRARIES/core_intermediates/classes.jar
         */

        if ("tools".equals(parentFileName)) {
            File sdkRoot = adb.getParentFile().getParentFile();
            logger.fine("using android sdk: " + sdkRoot);

            List<File> platforms = Arrays.asList(new File(sdkRoot, "platforms").listFiles());
            Collections.sort(platforms, ORDER_BY_NAME);
            File newestPlatform = platforms.get(platforms.size() - 1);
            logger.fine("using android platform: " + newestPlatform);

            return new AndroidSdk(new File(newestPlatform, "android.jar"));

        } else if ("bin".equals(parentFileName)) {
            File sourceRoot = adb.getParentFile().getParentFile()
                    .getParentFile().getParentFile().getParentFile();
            logger.fine("using android build tree: " + sourceRoot);
            File coreClasses = new File(sourceRoot
                    + "/out/target/common/obj/JAVA_LIBRARIES/core_intermediates/classes.jar");
            return new AndroidSdk(coreClasses);

        } else {
            throw new RuntimeException("Couldn't derive Android home from " + adb);
        }
    }

    public File getAndroidClasses() {
        return androidClasses;
    }

    /**
     * Converts all the .class files on 'classpath' into a dex file written to 'output'.
     */
    public void dex(File output, Classpath classpath) {
        output.getParentFile().mkdirs();
        File key = DEX_CACHE.makeKey(classpath);
        if (key != null && key.exists()) {
            logger.fine("dex cache hit for " + classpath);
            new Command.Builder().args("cp", key, output).execute();
            return;
        }
        /*
         * We pass --core-library so that we can write tests in the
         * same package they're testing, even when that's a core
         * library package. If you're actually just using this tool to
         * execute arbitrary code, this has the unfortunate
         * side-effect of preventing "dx" from protecting you from
         * yourself.
         *
         * Memory options pulled from build/core/definitions.mk to
         * handle large dx input when building dex for APK.
         */
        new Command.Builder()
                .args("dx")
                .args("-JXms16M")
                .args("-JXmx1536M")
                .args("--dex")
                .args("--output=" + output)
                .args("--core-library")
                .args(Strings.objectsToStrings(classpath.getElements()))
                .execute();
        DEX_CACHE.insert(key, output);
    }

    public void packageApk(File apk, File manifest) {
        new Command("aapt", "package",
                "-F", apk.getPath(),
                "-M", manifest.getPath(),
                "-I", androidClasses.getPath())
                .execute();
    }

    public void addToApk(File apk, File dex) {
        new Command("aapt", "add", "-k", apk.getPath(), dex.getPath()).execute();
    }

    public void mkdir(File name) {
        new Command("adb", "shell", "mkdir", name.getPath()).execute();
    }

    public void rm(File name) {
        new Command("adb", "shell", "rm", "-r", name.getPath()).execute();
    }

    public void push(File local, File remote) {
        new Command("adb", "push", local.getPath(), remote.getPath()).execute();
    }

    public void install(File apk) {
        new Command("adb", "install", "-r", apk.getPath()).execute();
    }

    public void uninstall(String packageName) {
        new Command("adb", "uninstall", packageName).execute();
    }

    public void forwardTcp(int localPort, int devicePort) {
        new Command("adb", "forward", "tcp:" + localPort, "tcp:" + devicePort).execute();
    }

    public void waitForDevice() {
        new Command("adb", "wait-for-device").execute();
    }

    /**
     * Loop until we see a non-empty directory on the device. For
     * example, wait until /sdcard is mounted.
     */
    public void waitForNonEmptyDirectory(File path, long timeoutSeconds) {
        waitFor(false, path, timeoutSeconds);
    }

    private void waitFor(boolean file, File path, long timeoutSeconds) {
        final int millisPerSecond = 1000;
        final long start = System.currentTimeMillis();
        final long deadline = start + (millisPerSecond * timeoutSeconds);

        while (true) {
            final long remainingSeconds = ((deadline - System.currentTimeMillis())
                                           / millisPerSecond);
            String pathArgument = path.getPath();
            if (!file) {
                pathArgument += "/";
            }
            Command command = new Command("adb", "shell", "ls", pathArgument);
            List<String> output;
            try {
                output = command.executeWithTimeout(remainingSeconds);
            } catch (TimeoutException e) {
                throw new RuntimeException("Timed out after " + timeoutSeconds +
                                           " seconds waiting for file " + path, e);
            }
            try {
                Thread.sleep(millisPerSecond);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (file) {
                // for files, we expect one line of output that matches the filename
                if (output.size() == 1 && output.get(0).equals(path.getPath())) {
                    return;
                }
            } else {
                // for a non empty directory, we just want any output
                if (!output.isEmpty()) {
                    return;
                }
            }
        }
    }
}
