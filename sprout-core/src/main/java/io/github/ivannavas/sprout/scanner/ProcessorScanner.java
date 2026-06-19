package io.github.ivannavas.sprout.scanner;

import io.github.ivannavas.sprout.annotation.Processor;
import io.github.ivannavas.sprout.processor.ComponentProcessor;

import java.io.File;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class ProcessorScanner {

    public ProcessorScanner(Class<?> mainClass, Logger logger) {
        this.logger = logger;
        this.loader = mainClass.getClassLoader();
        this.classpathRoots = resolveClasspathRoots(mainClass);
    }

    private final Logger logger;
    private final ClassLoader loader;
    private final List<File> classpathRoots;

    public Map<Class<? extends Annotation>, Class<? extends ComponentProcessor>> scan() {
        Set<String> classNames = new LinkedHashSet<>();
        for (File root : classpathRoots) {
            if (root.isDirectory()) {
                classNames.addAll(scanDirectory(root));
            } else if (root.isFile()) {
                classNames.addAll(scanJar(root));
            }
        }
        return resolveProcessors(classNames);
    }

    @SuppressWarnings("unchecked")
    private Map<Class<? extends Annotation>, Class<? extends ComponentProcessor>> resolveProcessors(Set<String> classNames) {
        List<Class<? extends ComponentProcessor>> processors = new ArrayList<>();
        for (String name : classNames) {
            try {
                Class<?> clazz = Class.forName(name, false, loader);
                if (!clazz.isAnnotationPresent(Processor.class)) continue;
                if (!ComponentProcessor.class.isAssignableFrom(clazz)) continue;
                processors.add((Class<? extends ComponentProcessor>) clazz);
            } catch (Throwable t) {
                // The whole classpath is scanned, so unrelated classes with missing optional
                // dependencies are expected to fail loading; keep it quiet.
                logger.log(Level.FINE, "Sprout: skipped class " + name + " - " + t);
            }
        }

        Set<Class<?>> overridden = new HashSet<>();
        for (Class<? extends ComponentProcessor> processor : processors) {
            Class<? extends ComponentProcessor> target = processor.getAnnotation(Processor.class).overrides();
            if (target != ComponentProcessor.class) {
                overridden.add(target);
            }
        }

        Map<Class<? extends Annotation>, Class<? extends ComponentProcessor>> result = new HashMap<>();
        for (Class<? extends ComponentProcessor> processor : processors) {
            if (overridden.contains(processor)) {
                logger.info("Sprout: ignoring processor " + processor.getName() + " (overridden)");
                continue;
            }
            Class<? extends Annotation> annotation = processor.getAnnotation(Processor.class).value();
            result.put(annotation, processor);
        }
        return result;
    }

    private List<File> resolveClasspathRoots(Class<?> mainClass) {
        Set<File> roots = new LinkedHashSet<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!entry.isBlank()) roots.add(new File(entry));
        }
        // Fall back to the code sources in case they are not part of java.class.path (e.g. fat jars).
        addCodeSource(roots, mainClass);
        addCodeSource(roots, ProcessorScanner.class);
        return new ArrayList<>(roots);
    }

    private void addCodeSource(Set<File> roots, Class<?> clazz) {
        try {
            URI uri = clazz.getProtectionDomain().getCodeSource().getLocation().toURI();
            roots.add(new File(uri));
        } catch (Exception e) {
            // Expected when the code source is a nested URL (e.g. a Spring Boot fat jar); the outer
            // jar is already scanned via java.class.path, so this is not fatal.
            logger.log(Level.FINE, "Sprout: cannot locate code source for " + clazz + " - " + e);
        }
    }

    private List<String> scanJar(File jarFile) {
        List<String> names = new ArrayList<>();
        try {
            JarClassScanner.forEachClassName(jarFile, names::add);
        } catch (Exception e) {
            throw new IllegalStateException("Sprout: failed scanning jar " + jarFile, e);
        }
        return names;
    }

    private List<String> scanDirectory(File root) {
        Path base = root.toPath();
        try (Stream<Path> walk = Files.walk(base)) {
            return walk.map(p -> base.relativize(p).toString().replace(File.separatorChar, '/'))
                    .filter(this::isClassEntry)
                    .map(p -> p.substring(0, p.length() - 6).replace('/', '.'))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Sprout: failed scanning dir " + root, e);
        }
    }

    private boolean isClassEntry(String entry) {
        return entry.endsWith(".class")
                && !entry.endsWith("module-info.class")
                && !entry.endsWith("package-info.class");
    }
}
