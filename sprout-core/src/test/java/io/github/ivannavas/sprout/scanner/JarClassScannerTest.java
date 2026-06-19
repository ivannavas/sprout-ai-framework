package io.github.ivannavas.sprout.scanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarClassScannerTest {

    @Test
    void findsClassesInPlainNestedAndBootInfLayouts(@TempDir Path tempDir) throws IOException {
        byte[] nestedJar = jarBytes("com/lib/Bar.class");

        File outer = tempDir.resolve("app.jar").toFile();
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(outer))) {
            writeEntry(out, "com/plain/Baz.class", new byte[]{0x01});
            writeEntry(out, "BOOT-INF/classes/com/app/Foo.class", new byte[]{0x01});
            writeEntry(out, "BOOT-INF/classes/com/app/package-info.class", new byte[]{0x01});
            writeEntry(out, "BOOT-INF/lib/dep.jar", nestedJar);
        }

        List<String> names = new ArrayList<>();
        JarClassScanner.forEachClassName(outer, names::add);

        assertTrue(names.contains("com.plain.Baz"), names + " should contain a plain jar class");
        assertTrue(names.contains("com.app.Foo"), names + " should contain a BOOT-INF/classes class");
        assertTrue(names.contains("com.lib.Bar"), names + " should contain a BOOT-INF/lib nested-jar class");
        assertFalse(names.contains("com.app.package-info"), "package-info should be skipped");
    }

    private static byte[] jarBytes(String... classEntries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bos)) {
            for (String entry : classEntries) {
                writeEntry(out, entry, new byte[]{0x01});
            }
        }
        return bos.toByteArray();
    }

    private static void writeEntry(JarOutputStream out, String name, byte[] content) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(content);
        out.closeEntry();
    }
}
