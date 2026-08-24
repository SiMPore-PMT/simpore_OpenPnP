package org.openpnp.scripting;

import org.apache.commons.io.FileUtils;
import org.openpnp.Translations;
import org.openpnp.util.UiUtils;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class ScriptFileWatcher {
    private final Scripting scripting;
    private File scriptsDirectory;

    JMenu menu;
    private JMenuItem openScriptsDirectoryMenuItem;
    WatchService fileWatcher;
    private final Set<Path> watchedDirectories = new HashSet<>();

    public ScriptFileWatcher(Scripting scripting) {
        this.scripting = scripting;
        scriptsDirectory = scripting.getScriptsDirectory();

        copyExampleScripts();
    }

    private void copyExampleScripts() {
        // TODO: It would be better if we just copied all the files from the Examples
        // directory in the jar, but this is relatively difficult to do.
        // There is some information on how to do it in:
        // http://stackoverflow.com/questions/1386809/copy-directory-from-a-jar-file
        File examplesDir = new File(scripting.getScriptsDirectory(), "Examples");
        examplesDir.mkdirs();
        String[] exampleScripts =
                new String[] {
                        "JavaScript/Call_Java.js",
                        "JavaScript/Hello_World.js",
                        "JavaScript/Move_Machine.js",
                        "JavaScript/Pipeline.js",
                        "JavaScript/Print_Scripting_Info.js",
                        "JavaScript/QrCodeXout.js",
                        "JavaScript/Reset_Strip_Feeders.js",
                        "JavaScript/Utility.js",
                        "Python/call_java.py",
                        "Python/move_machine.py",
                        "Python/print_hallo_openpnp.py",
                        "Python/print_methods_vars.py",
                        "Python/print_nozzle_info.py",
                        "Python/print_scripting_info.py",
                        "Python/use_module.py",
                        "Python/utility.py"
                };
        for (String name : exampleScripts) {
            try {
                File file = new File(examplesDir, name);
                if (file.exists()) {
                    continue;
                }
                FileUtils.copyURLToFile(ClassLoader.getSystemResource("scripts/Examples/" + name),
                        file);
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void setMenu(JMenu menu) {
        setupFileWatcher();

        this.menu = menu;
        // Add a separator and the Refresh Scripts and Open Scripts Directory items
        menu.addSeparator();
        menu.add(new AbstractAction(Translations.getString("Scripting.Action.Refresh")) { //$NON-NLS-1$
            {
                putValue(MNEMONIC_KEY, KeyEvent.VK_R);
            }

            @Override
            public void actionPerformed(ActionEvent e) {
                synchronizeMenu(menu, scriptsDirectory);
            }
        });
        openScriptsDirectoryMenuItem = menu.add(new AbstractAction(
                Translations.getString("Scripting.Action.OpenScriptsDirectory")) { //$NON-NLS-1$
            {
                putValue(MNEMONIC_KEY, KeyEvent.VK_O);
            }

            @Override
            public void actionPerformed(ActionEvent e) {
                UiUtils.messageBoxOnException(() -> {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().open(scriptsDirectory);
                    }
                });
            }
        });
        AbstractAction btnClearPool = new AbstractAction(
                Translations.getString("Scripting.Action.ClearScriptingEnginePool")) {
            {
                putValue(MNEMONIC_KEY, KeyEvent.VK_C);
            }

            @Override
            public void actionPerformed(ActionEvent e) {
                scripting.clearScriptingEnginePool();
            }
        };
        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                btnClearPool.setEnabled(scripting.enableClearScriptingEnginePool());
            }

            @Override
            public void menuDeselected(MenuEvent e) {}

            @Override
            public void menuCanceled(MenuEvent e) {}
        });

        menu.add(btnClearPool);

        // Synchronize the menu
        synchronizeMenu(menu, scriptsDirectory);
    }

    public void setOpenScriptsDirectoryVisible(boolean visible) {
        if (openScriptsDirectoryMenuItem != null) {
            openScriptsDirectoryMenuItem.setVisible(visible);
            openScriptsDirectoryMenuItem.setEnabled(visible);
        }
    }

    /**
     * Changes the directory represented by the scripts menu. The configured scripts directory is
     * still used by default, while restricted users can be presented with a dedicated subtree.
     */
    public synchronized void setScriptsDirectory(File scriptsDirectory) {
        if (Objects.equals(this.scriptsDirectory, scriptsDirectory)) {
            return;
        }
        this.scriptsDirectory = scriptsDirectory;
        if (menu != null) {
            for (JMenuItem item : getScriptMenuItems(menu)) {
                menu.remove(item);
            }
        }
        if (scriptsDirectory != null && scriptsDirectory.isDirectory()) {
            watchDirectory(scriptsDirectory);
        }
        synchronizeMenu(menu, scriptsDirectory);
    }

    private void setupFileWatcher() {
        // Add a file watcher so that we can be notified if any scripts change
        try {
            fileWatcher = FileSystems.getDefault().newWatchService();
            watchDirectory(scriptsDirectory);
            Thread thread = new Thread(() -> {
                for (;;) {
                    try {
                        // wait for an event
                        WatchKey key = fileWatcher.take();
                        key.pollEvents();
                        key.reset();
                        // rescan
                        watchDirectory(scriptsDirectory);
                        synchronizeMenu(menu, scriptsDirectory);
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void watchDirectory(File directory) {
        if (fileWatcher == null || directory == null || !directory.isDirectory()) {
            return;
        }
        Path path = directory.toPath();
        if (!watchedDirectories.add(path)) {
            return;
        }
        try {
            path.register(fileWatcher, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
        }
        catch (Exception e) {
            watchedDirectories.remove(path);
            e.printStackTrace();
        }
    }

    private synchronized void synchronizeMenu(JMenu menu, File directory) {
        if (menu == null || directory == null || !directory.isDirectory()) {
            return;
        }
        // Remove any menu items that don't have a matching entry in the directory
        String[] directoryEntries = directory.list();
        Set<String> filenames = directoryEntries == null ? Collections.emptySet()
                : new HashSet<>(Arrays.asList(directoryEntries));
        for (JMenuItem item : getScriptMenuItems(menu)) {
            if (!filenames.contains(item.getText())) {
                menu.remove(item);
            }
        }

        // Add any scripts not already in the menu
        Set<String> itemNames = getScriptMenuItems(menu).stream().map(JMenuItem::getText)
                .collect(Collectors.toSet());
        for (File script : FileUtils.listFiles(directory, scripting.getExtensions(), false)) {
            if (!script.isFile()) {
                continue;
            }
            if (itemNames.contains(script.getName())) {
                continue;
            }
            JMenuItem item = new JMenuItem(script.getName());
            item.addActionListener((e) -> {
                UiUtils.messageBoxOnException(() -> scripting.execute(script));
            });
            addSorted(menu, item);
        }

        // And add any directories not already in the menu
        itemNames = getScriptMenuItems(menu).stream().map(JMenuItem::getText)
                .collect(Collectors.toSet());
        for (File d : directory.listFiles(File::isDirectory)) {
            if (d.equals(scripting.getEventsDirectory())) {
                continue;
            }
            if (new File(d, ".ignore").exists()) {
                continue;
            }
            if (!itemNames.contains(d.getName())) {
                JMenu m = new JMenu(d.getName());
                addSorted(menu, m);
                watchDirectory(d);
            }
        }

        // Synchronize all of the sub-menus with their directories
        for (JMenuItem item : getScriptMenuItems(menu)) {
            if (item instanceof JMenu) {
                synchronizeMenu((JMenu) item, new File(directory, item.getText()));
            }
        }
    }

    private void addSorted(JMenu menu, JMenuItem item) {
        if (menu.getItemCount() == 0) {
            menu.add(item);
            return;
        }
        for (int i = 0; i < menu.getItemCount(); i++) {
            JMenuItem existingItem = menu.getItem(i);
            if (existingItem == null || item.getText().toLowerCase()
                    .compareTo(existingItem.getText().toLowerCase()) <= 0) {
                menu.insert(item, i);
                return;
            }
        }
        menu.add(item);
    }

    private java.util.List<JMenuItem> getScriptMenuItems(JMenu menu) {
        List<JMenuItem> items = new ArrayList<>();
        for (int i = 0; i < menu.getItemCount(); i++) {
            // Once we hit the separator we stop
            if (menu.getItem(i) == null) {
                break;
            }
            items.add(menu.getItem(i));
        }
        return items;
    }
}
