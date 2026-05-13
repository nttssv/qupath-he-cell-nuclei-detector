package org.prototype1.qupath;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class Prototype1Extension implements QuPathExtension {

    @Override
    public void installExtension(QuPathGUI qupath) {
        try {
            Object menu = qupath.getClass()
                    .getMethod("getMenu", String.class, boolean.class)
                    .invoke(qupath, "Extensions>Prototype 1", true);

            Object runItem = createMenuItem(
                    "Run on selected annotation(s)",
                    () -> runBundledScript(qupath, "/org/prototype1/qupath/run_selected_annotations.groovy")
            );
            addMenuItem(menu, runItem);

            Object openReadmeItem = createMenuItem(
                    "Open README",
                    () -> runBundledScript(qupath, "/org/prototype1/qupath/open_readme.groovy")
            );
            addMenuItem(menu, openReadmeItem);
        } catch (Exception e) {
            throw new RuntimeException("Unable to install Prototype 1 extension", e);
        }
    }

    @Override
    public String getName() {
        return "Prototype 1 Cell Segmentation";
    }

    @Override
    public String getDescription() {
        return "Runs the reviewed Prototype 1 adrenal H&E cell segmentation pipeline from QuPath.";
    }

    private static Object createMenuItem(String text, Runnable action) throws Exception {
        Class<?> menuItemClass = Class.forName("javafx.scene.control.MenuItem");
        Object item = menuItemClass.getConstructor(String.class).newInstance(text);

        Class<?> eventHandlerClass = Class.forName("javafx.event.EventHandler");
        Object handler = Proxy.newProxyInstance(
                Prototype1Extension.class.getClassLoader(),
                new Class<?>[]{eventHandlerClass},
                (proxy, method, args) -> {
                    if ("handle".equals(method.getName())) {
                        action.run();
                    }
                    return null;
                }
        );
        menuItemClass.getMethod("setOnAction", eventHandlerClass).invoke(item, handler);
        return item;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void addMenuItem(Object menu, Object item) throws Exception {
        Object items = menu.getClass().getMethod("getItems").invoke(menu);
        ((List) items).add(item);
    }

    private static void runBundledScript(QuPathGUI qupath, String resourceName) {
        try (InputStream stream = Prototype1Extension.class.getResourceAsStream(resourceName)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled script: " + resourceName);
            }
            String script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            qupath.getClass()
                    .getMethod("runScript", File.class, String.class)
                    .invoke(qupath, null, script);
        } catch (Exception e) {
            throw new RuntimeException("Unable to run Prototype 1 script " + resourceName, e);
        }
    }
}
