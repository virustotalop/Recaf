package me.coley.recaf.util;

import com.google.common.reflect.ClassPath;;

import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static me.coley.recaf.util.Log.*;
import static java.lang.Class.forName;
import static java.lang.invoke.MethodType.*;
/**
 * Classpath utility.
 *
 * @author Matt
 * @author Andy Li
 * @author xxDark
 */
@SuppressWarnings("UnstableApiUsage")
public class ClasspathUtil {
	/**
	 * The system classloader, provided by {@link ClassLoader#getSystemClassLoader()}.
	 */
	public static final ClassLoader scl = ClassLoader.getSystemClassLoader();

	/**
	 * The system classpath.
	 */
	public static final ClassPath cp;

	/**
	 * A sorted, unmodifiable list of all class names in {@linkplain #cp the system classpath}.
	 */
	private static final List<String> systemClassNames;

	static {
		ClassPathScanner scanner = new ClassPathScanner();
		scanner.scan(scl);
		cp = scanner.classPath;
		systemClassNames = Collections.unmodifiableList(scanner.internalNames);
	}

	/**
	 * Check if a resource exists in the current classpath.
	 *
	 * @param path
	 *            Path to resource.
	 * @return {@code true} if resource exists. {@code false} otherwise.
	 */
	public static boolean resourceExists(String path) {
		if (!path.startsWith("/"))
			path = "/" + path;
		return ClasspathUtil.class.getResource(path) != null;
	}

	/**
	 * Fetch a resource as a stream in the current classpath.
	 *
	 * @param path
	 * 		Path to resource.
	 *
	 * @return Stream of resource.
	 */
	public static InputStream resource(String path) {
		if (!path.startsWith("/"))
			path = "/" + path;
		return ClasspathUtil.class.getResourceAsStream(path);
	}

	/**
	 * @return A sorted, unmodifiable list of all class names in the system classpath.
	 */
	public static List<String> getSystemClassNames() {
		return systemClassNames;
	}

	/**
	 * Checks if bootstrap classes is found in {@link #getSystemClassNames()}.
	 * @return {@code true} if they do, {@code false} if they don't
	 */
	public static boolean isBootstrapClassesFound() {
		return checkBootstrapClassExists(getSystemClassNames());
	}

	/**
	 * Returns the class associated with the specified name, using
	 * {@linkplain #scl the system class loader}.
	 * <br> The class will not be initialized if it has not been initialized earlier.
	 * <br> This is equivalent to {@code Class.forName(className, false, ClassLoader
	 * .getSystemClassLoader())}
	 *
	 * @param className
	 * 		The fully quantified class name.
	 *
	 * @return class object representing the desired class
	 *
	 * @throws ClassNotFoundException
	 * 		if the class cannot be located by the system class loader
	 * @see Class#forName(String, boolean, ClassLoader)
	 */
	public static Class<?> getSystemClass(String className) throws ClassNotFoundException {
		return forName(className, false, ClasspathUtil.scl);
	}

	/**
	 * Returns the class associated with the specified name, using
	 * {@linkplain #scl the system class loader}.
	 * <br> The class will not be initialized if it has not been initialized earlier.
	 * <br> This is equivalent to {@code Class.forName(className, false, ClassLoader
	 * .getSystemClassLoader())}
	 *
	 * @param className
	 * 		The fully quantified class name.
	 *
	 * @return class object representing the desired class,
	 * or {@code null} if it cannot be located by the system class loader
	 *
	 * @see Class#forName(String, boolean, ClassLoader)
	 */
	public static Optional<Class<?>> getSystemClassIfExists(String className) {
		try {
			return Optional.of(getSystemClass(className));
		} catch (ClassNotFoundException | NullPointerException ex) {
			return Optional.empty();
		}
	}

	/**
	 * Internal utility to check if bootstrap classes exist in a list of class names.
	 */
	private static boolean checkBootstrapClassExists(Collection<String> names) {
		String name = Object.class.getName();
		return names.contains(name) || names.contains(name.replace('.', '/'));
	}


	/**
	 * Utility class for easy state management.
	 */
	@SuppressWarnings("unchecked")
	private static class ClassPathScanner {
		ClassPath classPath;
		Set<String> build = new LinkedHashSet<>();
		List<String> names;
		ArrayList<String> internalNames;

		private void updateClassPath(ClassLoader loader) {
			try {
				classPath = ClassPath.from(loader);
				Set<String> tmp = classPath.getResources().stream()
						.filter(ClassPath.ClassInfo.class::isInstance)
						.map(ClassPath.ClassInfo.class::cast)
						.map(ClassPath.ClassInfo::getName)
						.collect(Collectors.toCollection(LinkedHashSet::new));
				build.addAll(tmp);
			} catch (IOException e) {
				throw new UncheckedIOException("Unable to scan classpath entries: " +
						loader.getClass().getName(), e);
			}
		}

		private boolean checkBootstrapClass() {
			return checkBootstrapClassExists(build);
		}

		void scan(ClassLoader classLoader) {
			updateClassPath(classLoader);

			// In some JVM implementation, the bootstrap class loader is implemented directly in native code
			// and does not exist as a ClassLoader instance. Unfortunately, Oracle JVM is one of them.
			//
			// Considering Guava's ClassPath util works (and can only work) by scanning urls from an URLClassLoader,
			// this means it cannot find any of the standard API like java.lang.Object
			// without explicitly specifying `-classpath=rt.jar` etc. in the launch arguments
			// (only IDEs' Run/Debug seems to do that automatically)
			//
			// It further means that in most of the circumstances (including `java -jar recaf.jar`)
			// auto-completion will not able to suggest internal names of any of the classes under java.*,
			// which will largely reduce the effectiveness of the feature.
			if (!checkBootstrapClass()) {
				float vmVersion = Float.parseFloat(System.getProperty("java.class.version")) - 44;
				if (vmVersion < 9) {
					try {
						Method method = ClassLoader.class.getDeclaredMethod("getBootstrapClassPath");
						method.setAccessible(true);
						Field field = URLClassLoader.class.getDeclaredField("ucp");
						field.setAccessible(true);

						Object bootstrapClasspath = method.invoke(null);
						scanBootstrapClasspath(field, classLoader, bootstrapClasspath);
						verifyScan();
					} catch (ReflectiveOperationException | SecurityException e) {
						throw new ExceptionInInitializerError(e);
					}
				} else {
					try {
						// Before we will do that, break into Jigsaw module system to grant full access
						Class<?> moduleClass = forName("java.lang.Module");
						Class<?> layer = forName("java.lang.ModuleLayer");
						Field lookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
						lookupField.setAccessible(true);
						MethodHandles.Lookup lookup = (MethodHandles.Lookup) lookupField.get(null);
						MethodHandle export = lookup
								.findVirtual(moduleClass, "implAddOpens", methodType(Void.TYPE, String.class));
						MethodHandle getPackages = lookup
								.findVirtual(moduleClass, "getPackages", methodType(Set.class));
						MethodHandle getModule = lookup
								.findVirtual(Class.class, "getModule", methodType(moduleClass));
						MethodHandle getLayer = lookup
								.findVirtual(moduleClass, "getLayer", methodType(layer));
						MethodHandle layerModules = lookup
								.findVirtual(layer, "modules", methodType(Set.class));
						MethodHandle unnamedModule = lookup
								.findVirtual(ClassLoader.class, "getUnnamedModule", methodType(moduleClass));
						Set<Object> modules = new HashSet<>();

						Object ourModule = getModule.invoke(ClasspathUtil.class);
						Object ourLayer = getLayer.invoke(ourModule);
						if (ourLayer != null) {
							modules.addAll((Collection<?>) layerModules.invoke(ourLayer));
						}
						modules.addAll(
								(Collection<?>) layerModules
										.invoke(lookup.findStatic(layer, "boot", methodType(layer))
												.invoke()));
						for (ClassLoader c = ClasspathUtil.class.getClassLoader(); c != null; c = c.getParent()) {
							modules.add(unnamedModule.invoke(c));
						}

						for (Object impl : modules) {
							for (Object name : (Collection<?>) getPackages.invoke(impl)) {
								export.invoke(impl, name);
							}
						}

						if (vmVersion >= 12) {
							Class<?> reflectionClass = forName("jdk.internal.reflect.Reflection");
							MethodHandle getMapHandle = lookup.findStaticGetter(reflectionClass,
									"fieldFilterMap", Map.class);
							// Map is immutable, thanks Oracle
							Map<Class<?>, ?> fieldFilterMap = new HashMap<>((Map<Class<?>, ?>)
									getMapHandle.invokeExact());
							fieldFilterMap.remove(Field.class);
							fieldFilterMap.remove(ClassLoader.class);
							lookup.findStaticSetter(reflectionClass, "fieldFilterMap", Map.class)
									.invokeExact(fieldFilterMap);
						}

						Method method = forName("jdk.internal.loader.ClassLoaders").getDeclaredMethod("bootLoader");
						method.setAccessible(true);
						Object bootLoader = method.invoke(null);
						Field field = bootLoader.getClass().getSuperclass().getDeclaredField("ucp");
						field.setAccessible(true);

						Object bootstrapClasspath = field.get(bootLoader);
						if (bootstrapClasspath != null)
							scanBootstrapClasspath(URLClassLoader.class.getDeclaredField("ucp"),
									classLoader, bootstrapClasspath);

						// Now, scan all modules
						Class<?> loadedModuleClass = forName("jdk.internal.loader.BuiltinClassLoader$LoadedModule");
						Method mref = loadedModuleClass.getDeclaredMethod("mref");
						mref.setAccessible(true);
						Class<?> mrefClass = mref.getReturnType();
						Method openReader = mrefClass.getDeclaredMethod("open");
						openReader.setAccessible(true);
						Method closeReader = openReader.getReturnType().getDeclaredMethod("close");
						closeReader.setAccessible(true);
						Method listReader = openReader.getReturnType().getDeclaredMethod("list");
						listReader.setAccessible(true);
						Field bootModulesField = bootLoader.getClass().getSuperclass()
								.getDeclaredField("packageToModule");
						bootModulesField.setAccessible(true);
						Collection<?> packageToModule = ((Map<String, ?>) bootModulesField.get(null)).values();
						for (Object module : packageToModule) {
							// Scan it!
							Object ref = mref.invoke(module);
							Object reader = openReader.invoke(ref);
							Stream<String> list = (Stream<String>) listReader.invoke(reader);
							list.filter(s -> s.endsWith(".class"))
									.forEach(s -> {
										build.add(s.replace('/', '.').substring(0, s.length() - 6));
									});
							// Manually add everything, can't use Guava here
							closeReader.invoke(reader);
						}

						verifyScan();
					} catch (Throwable t) {
						throw new ExceptionInInitializerError(t);
					}
				}
			}
			names = new ArrayList<>(build);
			// Map to internal names
			internalNames = names.stream()
					.map(name -> name.replace('.', '/'))
					.sorted(Comparator.naturalOrder())
					.collect(Collectors.toCollection(ArrayList::new));
			internalNames.trimToSize();
		}

		private void verifyScan() {
			if (!checkBootstrapClass()) {
				warn("Bootstrap classes are (still) missing from the classpath scan!");
			}
		}

		private void scanBootstrapClasspath(Field field, ClassLoader classLoader, Object bootstrapClasspath)
				throws IllegalAccessException, NoSuchFieldException {
			URLClassLoader dummyLoader = new URLClassLoader(new URL[0], classLoader);
			field.setAccessible(true);
			if (Modifier.isFinal(field.getModifiers())) {
				Field modifiers = Field.class.getDeclaredField("modifiers");
				modifiers.setAccessible(true);
				modifiers.setInt(field, field.getModifiers() & ~Modifier.FINAL);
			}
			// Change the URLClassPath in the dummy loader to the bootstrap one.
			field.set(dummyLoader, bootstrapClasspath);
			// And then feed it into Guava's ClassPath scanner.
			updateClassPath(dummyLoader);
		}
	}
}