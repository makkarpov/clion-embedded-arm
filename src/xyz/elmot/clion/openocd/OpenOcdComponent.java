package xyz.elmot.clion.openocd;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.RunContentExecutor;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.PtyCommandLine;
import com.intellij.execution.filters.Filter;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.util.ExecutionErrorDialog;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.FutureResult;
import org.jdesktop.swingx.util.OS;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.concurrent.Future;

public class OpenOcdComponent {

    public static final String SCRIPTS_PATH_SHORT = "scripts";
    public static final String SCRIPTS_PATH_LONG = "share/openocd/" + SCRIPTS_PATH_SHORT;
    public static final String BIN_OPENOCD;
    private static final String ERROR_PREFIX = "Error: ";
    private static final String[] IGNORED_STRINGS = { //todo take into use
            "clearing lockup after double fault",
            "LIB_USB_NOT_SUPPORTED"};

    private final static String[] FAIL_STRINGS = {
            "** Programming Failed **", "communication failure", "** OpenOCD init failed **"};
    private static final String FLASH_SUCCESS_TEXT = "** Programming Finished **";
    private static final Logger LOG = Logger.getInstance(OpenOcdRun.class);

    static {
        BIN_OPENOCD = "bin/openocd" + (OS.isWindows() ? ".exe" : "");
    }

    private final EditorColorsScheme myColorsScheme;
    private OSProcessHandler process;

    public OpenOcdComponent() {
        myColorsScheme = EditorColorsManager.getInstance().getGlobalScheme();
    }

    @SuppressWarnings("WeakerAccess")
    @NotNull
    public static GeneralCommandLine createOcdCommandLine(@NotNull Project project,
                                                          @Nullable OpenOcdConfiguration configuration,
                                                          @Nullable File fileToLoad,
                                                          @Nullable String additionalCommand,
                                                          boolean shutdown) throws ConfigurationException
    {
        OpenOcdSettingsState ocdSettings = project.getComponent(OpenOcdSettingsState.class);

        String boardConfigFile = OpenOcdConfiguration.actualBoardFile(configuration, ocdSettings);
        if (boardConfigFile == null || "".equals(boardConfigFile.trim())) {
            throw new ConfigurationException("Board Config file is not defined.\nPlease open OpenOCD settings and choose one.", "OpenOCD run error");
        }

        VirtualFile ocdHome = require(LocalFileSystem.getInstance().findFileByPath(ocdSettings.openOcdHome));
        VirtualFile ocdBinary = require(ocdHome.findFileByRelativePath(BIN_OPENOCD));
        File ocdBinaryIo = VfsUtil.virtualToIoFile(ocdBinary);
        GeneralCommandLine commandLine = new PtyCommandLine()
                .withWorkDirectory(ocdBinaryIo.getParentFile())
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                .withParameters("-c", "tcl_port disabled")
                .withExePath(ocdBinaryIo.getAbsolutePath());

        VirtualFile ocdScripts = require(OpenOcdSettingsState.findOcdScripts(ocdHome));
        commandLine.addParameters("-s", VfsUtil.virtualToIoFile(ocdScripts).getAbsolutePath());

        int gdbPort = OpenOcdConfiguration.actualGdbPort(configuration, ocdSettings);
        if (gdbPort != OpenOcdSettingsState.DEF_GDB_PORT) {
            commandLine.addParameters("-c", "gdb_port " + gdbPort);
        }

        int telnetPort = OpenOcdConfiguration.actualTelnetPort(configuration, ocdSettings);
        if (telnetPort != OpenOcdSettingsState.DEF_TELNET_PORT) {
            commandLine.addParameters("-c", "telnet_port " + telnetPort);
        }

        commandLine.addParameters("-f", boardConfigFile);
        String command = "";
        if (fileToLoad != null) {
            command += "program \"" + fileToLoad.getAbsolutePath().replace(File.separatorChar, '/') + "\";";
        }
        if (additionalCommand != null) {
            command += additionalCommand + ";";
        }
        if (shutdown) {
            command += "shutdown";
        }
        if (!command.isEmpty()) {
            commandLine.addParameters("-c", command);
        }
        return commandLine;
    }

    @NotNull
    private static VirtualFile require(VirtualFile fileToCheck) throws ConfigurationException {
        if (fileToCheck == null) {
            openOcdNotFound();
        }
        return fileToCheck;
    }

    private static void openOcdNotFound() throws ConfigurationException {
        throw new ConfigurationException("Please open settings dialog and fix OpenOCD home", "OpenOCD config error");
    }

    @SuppressWarnings("WeakerAccess")
    public void stopOpenOcd() {
        if (process == null || process.isProcessTerminated() || process.isProcessTerminating())
            return;
        ProgressManager.getInstance().executeNonCancelableSection(() -> {
            process.destroyProcess();
            process.waitFor(1000);
        });
    }

    @SuppressWarnings("WeakerAccess")
    public Future<STATUS> startOpenOcd(Project project, @Nullable OpenOcdConfiguration configuration,
                                       @Nullable File fileToLoad, @Nullable String additionalCommand) throws ConfigurationException {
        if (project == null) return new FutureResult<>(STATUS.FLASH_ERROR);
        GeneralCommandLine commandLine = createOcdCommandLine(project, configuration, fileToLoad, additionalCommand, false);
        if (process != null && !process.isProcessTerminated()) {
            LOG.info("openOcd is already run");
            return new FutureResult<>(STATUS.FLASH_ERROR);
        }

        try {
            process = new OSProcessHandler(commandLine) {
                @Override
                public boolean isSilentlyDestroyOnClose() {
                    return true;
                }
            };
            DownloadFollower downloadFollower = new DownloadFollower();
            process.addProcessListener(downloadFollower);
            RunContentExecutor openOCDConsole = new RunContentExecutor(project, process)
                    .withTitle("OpenOCD console")
                    .withActivateToolWindow(true)
                    .withFilter(new ErrorFilter(project))
                    .withStop(process::destroyProcess,
                            () -> !process.isProcessTerminated() && !process.isProcessTerminating());

            openOCDConsole.run();
            return downloadFollower;
        } catch (ExecutionException e) {
            ExecutionErrorDialog.show(e, "OpenOCD start failed", project);
            return new FutureResult<>(STATUS.FLASH_ERROR);
        }
    }

    public boolean isRun() {
        return process != null && !process.isProcessTerminated();
    }

    public enum STATUS {
        FLASH_SUCCESS,
        FLASH_WARNING,
        FLASH_ERROR,
    }

    private class ErrorFilter implements Filter {
        private final Project project;

        ErrorFilter(Project project) {
            this.project = project;
        }

        /**
         * Filters line by creating an instance of {@link Result}.
         *
         * @param line         The line to be filtered. Note that the line must contain a line
         *                     separator at the end.
         * @param entireLength The length of the entire text including the line passed for filtration.
         * @return <tt>null</tt>, if there was no match, otherwise, an instance of {@link Result}
         */
        @Nullable
        @Override
        public Result applyFilter(String line, int entireLength) {
            if (containsOneOf(line, FAIL_STRINGS)) {
                Informational.showFailedDownloadNotification(project);
                return new Result(0, line.length(), null,
                        myColorsScheme.getAttributes(ConsoleViewContentType.ERROR_OUTPUT_KEY)) {
                    @Override
                    public int getHighlighterLayer() {
                        return HighlighterLayer.ERROR;
                    }
                };
            } else if (line.contains(FLASH_SUCCESS_TEXT)) {
                Informational.showSuccessfulDownloadNotification(project);
            }
            return null;
        }
    }

    private class DownloadFollower extends FutureResult<STATUS> implements ProcessListener {

        @Override
        public void startNotified(@NotNull ProcessEvent event) {
        }

        @Override
        public void processTerminated(@NotNull ProcessEvent event) {
            try {
                if (!isDone()) {
                    set(STATUS.FLASH_ERROR);
                }
            } catch (Exception e) {
                set(STATUS.FLASH_ERROR);
            }
        }

        @Override
        public void processWillTerminate(@NotNull ProcessEvent event, boolean willBeDestroyed) {
            //nothing to do
        }

        @Override
        public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
            String text = event.getText().trim();
            if (containsOneOf(text, FAIL_STRINGS)) {
                reset();
                set(STATUS.FLASH_ERROR);
            } else if (text.equals(FLASH_SUCCESS_TEXT)) {
                reset();
                set(STATUS.FLASH_SUCCESS);
            } else if (text.startsWith(ERROR_PREFIX) && !containsOneOf(text, IGNORED_STRINGS)) {
                reset();
                set(STATUS.FLASH_WARNING);
            }
        }
    }

    private boolean containsOneOf(String text, String[] sampleStrings) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String sampleString : sampleStrings) {
            if (text.contains(sampleString)) return true;
        }
        return false;

    }
}
