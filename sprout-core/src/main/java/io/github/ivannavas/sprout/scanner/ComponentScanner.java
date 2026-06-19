package io.github.ivannavas.sprout.scanner;

import io.github.ivannavas.sprout.annotation.Component;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class ComponentScanner {

    public ComponentScanner(Class<?> mainClass, List<String> basePackages, Logger logger) {
        this.logger = logger;
        this.loader = mainClass.getClassLoader();
        this.basePackages = basePackages.isEmpty() ? List.of(mainClass.getPackageName()) : basePackages;
        this.classpathRoots = resolveClasspathRoots();
    }

    private final Logger logger;
    private final ClassLoader loader;
    private final List<String> basePackages;
    private final List<File> classpathRoots;

    public List<Class<?>> scan() {
        Set<String> classNames = new LinkedHashSet<>();
        for (File root : classpathRoots) {
            if (root.isDirectory()) {
                for (String basePackage : basePackages) {
                    classNames.addAll(scanDirectory(root, basePackage));
                }
            } else if (root.isFile()) {
                classNames.addAll(scanJar(root));
            }
        }

        List<Class<?>> components = new ArrayList<>();
        for (String name : classNames) {
            try {
                Class<?> clazz = Class.forName(name, false, loader);
                if (isComponent(clazz)) components.add(clazz);
            } catch (Throwable t) {
                logger.warning("Sprout: failed loading class " + name + " - " + t);
            }
        }
        return components;
    }

    private List<File> resolveClasspathRoots() {
        List<File> roots = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!entry.isBlank()) roots.add(new File(entry));
        }
        return roots;
    }

    private List<String> scanJar(File jarFile) {
        List<String> names = new ArrayList<>();
        try {
            JarClassScanner.forEachClassName(jarFile, name -> {
                for (String basePackage : basePackages) {
                    if (name.equals(basePackage) || name.startsWith(basePackage + ".")) {
                        names.add(name);
                        return;
                    }
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("Sprout: failed scanning jar " + jarFile, e);
        }
        return names;
    }

    private List<String> scanDirectory(File root, String basePackage) {
        Path base = root.toPath();
        Path start = base.resolve(basePackage.replace('.', '/'));
        if (!Files.isDirectory(start)) return List.of();
        try (Stream<Path> walk = Files.walk(start)) {
            return walk.filter(p -> p.toString().endsWith(".class"))
                    .map(p -> base.relativize(p).toString()
                            .replace(File.separatorChar, '.')
                            .replaceAll("\\.class$", ""))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Sprout: failed scanning dir " + root, e);
        }
    }

    private boolean isComponent(Class<?> clazz) {
        int mods = clazz.getModifiers();
        if (clazz.isInterface() || clazz.isAnnotation() || clazz.isEnum() || Modifier.isAbstract(mods)) {
            return false;
        }
        if (clazz.isAnnotationPresent(Component.class)) return true;
        for (Annotation ann : clazz.getAnnotations()) {
            if (ann.annotationType().isAnnotationPresent(Component.class)) return true;
        }
        return false;
    }
}
