// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.tests;

import org.junit.platform.engine.*;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.*;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.vintage.engine.descriptor.VintageTestDescriptor;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

// Used to run JUnit 5 tests via JUnit 5 runtime
@SuppressWarnings({"UseOfSystemOutOrSystemErr", "CallToPrintStackTrace"})
public final class JUnit5TeamCityRunnerForTestsOnClasspath {

  public static void main(String[] args) {
    try {
      Launcher launcher = LauncherFactory.create();

      ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
      // DiscoveryRequest first filters classes by ClassNameFilter, then loads class and runs additional checks:
      // presense of annotations, test methods, etc.
      // See usages of `org.junit.platform.commons.util.ClassFilter.match(java.lang.String)`.
      // ClassNameFilter could and will be called for every class in classpath, even non-test one, even for synthetic lambda classes.
      // That's why it should be fast and should not incur additional overhead, like checking whether it belongs to the current bucket.
      ClassNameFilter nameFilter;
      // PostDiscoveryFilter runs on already discovered classes and methods (TestDescriptors), so we could run more complex checks,
      // like determining whether it belongs to the current bucket.
      PostDiscoveryFilter postDiscoveryFilter;
      Set<Path> classPathRoots;
      try {
        nameFilter = createClassNameFilter(classLoader);
        postDiscoveryFilter = createPostDiscoveryFilter(classLoader);
        classPathRoots = getClassPathRoots(classLoader);
      }
      catch (Throwable e) {
        e.printStackTrace();
        System.exit(1);
        return;
      }
      System.out.println("Number of test engines: " + ServiceLoader.load(TestEngine.class).stream().count());

      List<? extends DiscoverySelector> selectors;
      if (classPathRoots != null) {
        selectors = DiscoverySelectors.selectClasspathRoots(classPathRoots);
      }
      else {
        selectors = Collections.singletonList(DiscoverySelectors.selectPackage(""));
      }
      LauncherDiscoveryRequest discoveryRequest = LauncherDiscoveryRequestBuilder.request()
        .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
        .selectors(selectors)
        .filters(nameFilter, postDiscoveryFilter, EngineFilter.excludeEngines(VintageTestDescriptor.ENGINE_ID)).build();
      TestPlan testPlan = launcher.discover(discoveryRequest);
      if (testPlan.containsTests()) {
        launcher.execute(testPlan, new JUnit5TeamCityRunnerForTestAllSuite.TCExecutionListener());
      }
      else {
        //see org.jetbrains.intellij.build.impl.TestingTasksImpl.NO_TESTS_ERROR
        System.exit(42);
      }
    }
    finally {
      System.exit(0);
    }
  }

  private static Set<Path> getClassPathRoots(ClassLoader classLoader) throws Throwable {
    //noinspection unchecked
    List<Path> paths = (List<Path>)MethodHandles.publicLookup()
      .findStatic(Class.forName("com.intellij.TestAll", false, classLoader),
                  "getClassRoots", MethodType.methodType(List.class))
      .invokeExact();
    if (paths == null) return null;
    // Skip jars and any other archives, otherwise we will end up with test classes from dependencies.
    return paths.stream().filter(Files::isDirectory).collect(Collectors.toSet());
  }

  private static ClassNameFilter createClassNameFilter(ClassLoader classLoader)
    throws NoSuchMethodException, ClassNotFoundException, IllegalAccessException {
    MethodHandle included = MethodHandles.publicLookup()
      .findStatic(Class.forName("com.intellij.TestCaseLoader", true, classLoader),
                  "isClassNameIncluded", MethodType.methodType(boolean.class, String.class));
    return new ClassNameFilter() {
      @Override
      public FilterResult apply(String className) {

        try {
          if ((boolean)included.invokeExact(className)) {
            return FilterResult.included(null);
          }
          return FilterResult.excluded(null);
        }
        catch (Throwable e) {
          return FilterResult.excluded(e.getMessage());
        }
      }
    };
  }

  private static PostDiscoveryFilter createPostDiscoveryFilter(ClassLoader classLoader)
    throws NoSuchMethodException, ClassNotFoundException, IllegalAccessException {
    MethodHandle included = MethodHandles.publicLookup()
      .findStatic(Class.forName("com.intellij.TestCaseLoader", true, classLoader),
                  "isClassIncluded", MethodType.methodType(boolean.class, String.class));
    return new PostDiscoveryFilter() {
      private FilterResult isIncluded(String className) {
        try {
          if ((boolean)included.invokeExact(className)) {
            return FilterResult.included(null);
          }
          return FilterResult.excluded(null);
        }
        catch (Throwable e) {
          return FilterResult.excluded(e.getMessage());
        }
      }

      @Override
      public FilterResult apply(TestDescriptor descriptor) {
        if (descriptor instanceof EngineDescriptor) {
          return FilterResult.included(null);
        }
        TestSource source = descriptor.getSource().orElse(null);
        if (source == null) {
          return FilterResult.included("No source for descriptor");
        }
        if (source instanceof MethodSource methodSource) {
          return isIncluded(methodSource.getClassName());
        }
        if (source instanceof ClassSource classSource) {
          return isIncluded(classSource.getClassName());
        }
        return FilterResult.included("Unknown source type " + source.getClass());
      }
    };
  }
}
