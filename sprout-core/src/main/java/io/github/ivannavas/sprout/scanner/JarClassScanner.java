package io.github.ivannavas.sprout.scanner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;

// Enumerates class names in a jar, transparently handling repackaged fat-jar layouts (classes under
// BOOT-INF/classes/ plus nested BOOT-INF/lib/*.jar) so scanning works from plain jars and from a
// repackaged executable jar alike.
final class JarClassScanner {

    private static final String BOOT_CLASSES = "BOOT-INF/classes/";
    private static final String BOOT_LIB = "BOOT-INF/lib/";
    private static final String CLASS_SUFFIX = ".class";

    private JarClassScanner() {
    }

    static void forEachClassName(File jarFile, Consumer<String> consumer) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (isClassEntry(name)) {
                    consumer.accept(toClassName(stripBootInfPrefix(name)));
                } else if (isNestedLib(name)) {
                    scanNestedJar(jar, entry, consumer);
                }
            }
        }
    }

    private static void scanNestedJar(JarFile outer, JarEntry libEntry, Consumer<String> consumer) throws IOException {
        try (InputStream in = outer.getInputStream(libEntry);
             JarInputStream nested = new JarInputStream(in)) {
            JarEntry entry;
            while ((entry = nested.getNextJarEntry()) != null) {
                String name = entry.getName();
                if (isClassEntry(name)) {
                    consumer.accept(toClassName(name));
                }
            }
        }
    }

    private static boolean isNestedLib(String entryName) {
        return entryName.startsWith(BOOT_LIB) && entryName.endsWith(".jar");
    }

    private static String stripBootInfPrefix(String entryName) {
        return entryName.startsWith(BOOT_CLASSES) ? entryName.substring(BOOT_CLASSES.length()) : entryName;
    }

    private static boolean isClassEntry(String entryName) {
        return entryName.endsWith(CLASS_SUFFIX)
                && !entryName.endsWith("module-info.class")
                && !entryName.endsWith("package-info.class");
    }

    private static String toClassName(String entryName) {
        return entryName.substring(0, entryName.length() - CLASS_SUFFIX.length()).replace('/', '.');
    }
}
