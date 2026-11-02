
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import javax.imageio.ImageIO;

public class ScreenCaptureApp {

    private static final Path SAVE_DIRECTORY =
            Path.of(System.getProperty("user.home"), "Desktop", "images");

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private static final String ENUMERATE_SCRIPT = """
        Add-Type -TypeDefinition @'
        using System;
        using System.Collections.Generic;
        using System.Runtime.InteropServices;
        using System.Text;

        public class WindowFinder {
            public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);

            [StructLayout(LayoutKind.Sequential)]
            public struct POINT {
                public int X;
                public int Y;
            }

            [StructLayout(LayoutKind.Sequential)]
            public struct RECT {
                public int Left;
                public int Top;
                public int Right;
                public int Bottom;
            }

            [StructLayout(LayoutKind.Sequential)]
            public struct WINDOWPLACEMENT {
                public int length;
                public int flags;
                public int showCmd;
                public POINT ptMinPosition;
                public POINT ptMaxPosition;
                public RECT rcNormalPosition;
            }

            [DllImport("user32.dll")]
            public static extern bool EnumWindows(
                EnumProc callback, IntPtr lParam);

            [DllImport("user32.dll")]
            public static extern bool IsWindow(IntPtr hWnd);

            [DllImport("user32.dll")]
            public static extern bool IsWindowVisible(IntPtr hWnd);

            [DllImport("user32.dll", CharSet = CharSet.Unicode)]
            public static extern int GetWindowText(
                IntPtr hWnd, StringBuilder text, int maxCount);

            [DllImport("user32.dll", CharSet = CharSet.Unicode)]
            public static extern int GetClassName(
                IntPtr hWnd, StringBuilder name, int maxCount);

            [DllImport("user32.dll")]
            public static extern uint GetWindowThreadProcessId(
                IntPtr hWnd, out uint processId);

            [DllImport("user32.dll")]
            public static extern bool GetWindowPlacement(
                IntPtr hWnd, ref WINDOWPLACEMENT placement);

            public static string[] GetWindows() {
                List<string> result = new List<string>();

                EnumWindows(delegate(IntPtr hWnd, IntPtr lParam) {
                    if (!IsWindow(hWnd) || !IsWindowVisible(hWnd))
                        return true;

                    StringBuilder title = new StringBuilder(512);
                    GetWindowText(hWnd, title, title.Capacity);

                    if (String.IsNullOrWhiteSpace(title.ToString()))
                        return true;

                    StringBuilder className = new StringBuilder(256);
                    GetClassName(hWnd, className, className.Capacity);

                    string cls = className.ToString();

                    if (cls == "Shell_TrayWnd" ||
                        cls == "Shell_SecondaryTrayWnd" ||
                        cls == "Progman" ||
                        cls == "WorkerW")
                        return true;

                    uint pid;
                    GetWindowThreadProcessId(hWnd, out pid);

                    if (pid == 0)
                        return true;

                    WINDOWPLACEMENT p = new WINDOWPLACEMENT();
                    p.length = Marshal.SizeOf(typeof(WINDOWPLACEMENT));

                    if (!GetWindowPlacement(hWnd, ref p))
                        return true;

                    RECT r = p.rcNormalPosition;
                    long width = Math.Abs((long)r.Right - r.Left);
                    long height = Math.Abs((long)r.Bottom - r.Top);
                    long area = width * height;

                    if (area <= 0)
                        return true;

                    result.Add(
                        hWnd.ToInt64() + "|" + pid + "|" + area);

                    return true;
                }, IntPtr.Zero);

                return result.ToArray();
            }
        }
        '@

        [WindowFinder]::GetWindows() -join ';'
        """;

    private static final String ACTION_SCRIPT = """
        Add-Type -TypeDefinition @'
        using System;
        using System.Runtime.InteropServices;
        using System.Threading;

        public class WindowActions {
            [DllImport("user32.dll")]
            public static extern bool IsWindow(IntPtr hWnd);

            [DllImport("user32.dll")]
            public static extern bool IsIconic(IntPtr hWnd);

            [DllImport("user32.dll")]
            public static extern bool ShowWindowAsync(
                IntPtr hWnd, int command);

            [DllImport("user32.dll")]
            public static extern bool SetForegroundWindow(IntPtr hWnd);

            [DllImport("user32.dll")]
            public static extern bool BringWindowToTop(IntPtr hWnd);

            [DllImport("user32.dll")]
            public static extern IntPtr GetForegroundWindow();

            [DllImport("user32.dll")]
            public static extern uint GetWindowThreadProcessId(
                IntPtr hWnd, out uint processId);

            [DllImport("kernel32.dll")]
            public static extern uint GetCurrentThreadId();

            [DllImport("user32.dll")]
            public static extern bool AttachThreadInput(
                uint firstThread, uint secondThread, bool attach);

            const int SW_RESTORE = 9;
            const int SW_MINIMIZE = 6;

            static bool IsOriginalWindow(
                IntPtr hWnd, uint expectedPid) {
                if (!IsWindow(hWnd))
                    return false;

                uint actualPid;
                GetWindowThreadProcessId(hWnd, out actualPid);

                return actualPid == expectedPid;
            }

            public static string Activate(
                IntPtr hWnd, uint expectedPid) {
                if (!IsOriginalWindow(hWnd, expectedPid))
                    return "WINDOW_NOT_FOUND";

                ShowWindowAsync(hWnd, SW_RESTORE);
                Thread.Sleep(100);

                IntPtr foreground = GetForegroundWindow();
                uint foregroundPid = 0;
                uint foregroundThread = foreground == IntPtr.Zero
                    ? 0
                    : GetWindowThreadProcessId(
                        foreground, out foregroundPid);

                uint targetPid;
                uint targetThread =
                    GetWindowThreadProcessId(hWnd, out targetPid);

                uint currentThread = GetCurrentThreadId();
                bool attachedTarget = false;
                bool attachedForeground = false;

                try {
                    if (targetThread != 0 &&
                        targetThread != currentThread) {
                        attachedTarget = AttachThreadInput(
                            currentThread, targetThread, true);
                    }

                    if (foregroundThread != 0 &&
                        foregroundThread != currentThread &&
                        foregroundThread != targetThread) {
                        attachedForeground = AttachThreadInput(
                            currentThread, foregroundThread, true);
                    }

                    BringWindowToTop(hWnd);
                    SetForegroundWindow(hWnd);
                }
                finally {
                    if (attachedForeground)
                        AttachThreadInput(
                            currentThread, foregroundThread, false);

                    if (attachedTarget)
                        AttachThreadInput(
                            currentThread, targetThread, false);
                }

                for (int i = 0; i < 6; i++) {
                    if (!IsOriginalWindow(hWnd, expectedPid))
                        return "WINDOW_NOT_FOUND";

                    if (GetForegroundWindow() == hWnd &&
                        !IsIconic(hWnd))
                        return "ACTIVATED";

                    SetForegroundWindow(hWnd);
                    Thread.Sleep(50);
                }

                return IsOriginalWindow(hWnd, expectedPid)
                    ? "ACTIVATION_UNCONFIRMED"
                    : "WINDOW_NOT_FOUND";
            }

            public static string Minimize(
                IntPtr hWnd, uint expectedPid) {
                if (!IsOriginalWindow(hWnd, expectedPid))
                    return "WINDOW_NOT_FOUND";

                ShowWindowAsync(hWnd, SW_MINIMIZE);

                for (int i = 0; i < 10; i++) {
                    if (!IsOriginalWindow(hWnd, expectedPid))
                        return "WINDOW_NOT_FOUND";

                    if (IsIconic(hWnd))
                        return "MINIMIZED";

                    Thread.Sleep(50);
                }

                return IsIconic(hWnd)
                    ? "MINIMIZED"
                    : "MINIMIZE_FAILED";
            }
        }
        '@

        $handle = [Int64]$env:TARGET_WINDOW_HANDLE
        $expectedPid = [UInt32]$env:TARGET_PROCESS_ID
        $hWnd = [IntPtr]$handle

        if ($env:WINDOW_ACTION -eq 'restore') {
            [WindowActions]::Activate($hWnd, $expectedPid)
        }
        elseif ($env:WINDOW_ACTION -eq 'minimize') {
            [WindowActions]::Minimize($hWnd, $expectedPid)
        }
        """;


    public static void main(String[] args) {
        Thread worker = new Thread(() -> {
            try {
                String result = processWindows();
                System.out.println(result);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        worker.setDaemon(false);
        worker.start();
    }


    private static String processWindows() throws Exception {
        Files.createDirectories(SAVE_DIRECTORY);

        List<WindowInfo> windows =
                parseWindows(runPowerShell(ENUMERATE_SCRIPT));

        if (windows.isEmpty()) {
            return "No existing application windows found."
                    + "\nSave folder: " + SAVE_DIRECTORY;
        }

        windows.sort(
                Comparator.comparingLong(WindowInfo::area).reversed());

        Rectangle screenBounds = getAllScreenBounds();
        Robot robot = new Robot();

        int saved = 0;
        int missing = 0;
        int minimizeFailures = 0;

        List<String> warnings = new ArrayList<>();
        List<Path> savedFiles = new ArrayList<>();

        for (WindowInfo window : windows) {
            String activation;

            try {
                activation = runAction(window, "restore");
            } catch (Exception ex) {
                activation = "ACTION_ERROR";
                warnings.add("Activation error for window "
                        + window.handle() + ": " + ex.getMessage());
            }

            if ("WINDOW_NOT_FOUND".equals(activation)) {
                missing++;
                continue;
            }

            Thread.sleep(150);

            BufferedImage screenshot =
                    robot.createScreenCapture(screenBounds);

            String fileName = String.format(
                    "screenshot_%03d_%s.png",
                    saved + 1,
                    LocalDateTime.now().format(TIME_FORMAT));

            Path outputFile = SAVE_DIRECTORY.resolve(fileName);

            boolean written = ImageIO.write(
                    screenshot, "png", outputFile.toFile());

            if (!written || !Files.exists(outputFile)
                    || Files.size(outputFile) == 0) {
                warnings.add("Could not save: " + outputFile);
                continue;
            }

            saved++;
            savedFiles.add(outputFile);

            try {
                String minimize = runAction(window, "minimize");

                if (!"MINIMIZED".equals(minimize))
                    minimizeFailures++;
            } catch (Exception ex) {
                minimizeFailures++;
                warnings.add("Minimize error for window "
                        + window.handle() + ": " + ex.getMessage());
            }
        }

        StringBuilder result = new StringBuilder()
                .append("Process completed.")
                .append("\nScreenshots saved: ").append(saved)
                .append("\nWindows no longer available: ").append(missing)
                .append("\nMinimize failures: ").append(minimizeFailures)
                .append("\nFolder: ").append(SAVE_DIRECTORY.toAbsolutePath());

        if (!warnings.isEmpty()) {
            result.append("\n\nWarnings:\n");

            for (String warning : warnings)
                result.append(warning).append('\n');
        }

        if (!savedFiles.isEmpty()) {
            result.append("\n\nLast saved file: ")
                    .append(savedFiles.get(savedFiles.size() - 1));
        }

        return result.toString();
    }

    private static List<WindowInfo> parseWindows(String output) {
        List<WindowInfo> windows = new ArrayList<>();

        if (output == null || output.isBlank())
            return windows;

        for (String entry : output.trim().split(";")) {
            String[] parts = entry.trim().split("\\|");

            if (parts.length != 3)
                continue;

            try {
                windows.add(new WindowInfo(
                        Long.parseLong(parts[0].trim()),
                        Long.parseLong(parts[1].trim()),
                        Long.parseLong(parts[2].trim())));
            } catch (NumberFormatException ignored) {
                // Ignore malformed output.
            }
        }

        return windows;
    }

    private static String runAction(
            WindowInfo window, String action)
            throws IOException, InterruptedException {

        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-EncodedCommand",
                encodePowerShell(ACTION_SCRIPT));

        builder.redirectErrorStream(true);

        builder.environment().put(
                "TARGET_WINDOW_HANDLE",
                Long.toString(window.handle()));

        builder.environment().put(
                "TARGET_PROCESS_ID",
                Long.toString(window.processId()));

        builder.environment().put("WINDOW_ACTION", action);

        return execute(builder);
    }

    private static String runPowerShell(String script)
            throws IOException, InterruptedException {

        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-EncodedCommand",
                encodePowerShell(script));

        builder.redirectErrorStream(true);

        return execute(builder);
    }

    private static String execute(ProcessBuilder builder)
            throws IOException, InterruptedException {

        Process process = builder.start();

        String output;

        try (var input = process.getInputStream()) {
            output = new String(
                    input.readAllBytes(), StandardCharsets.UTF_8);
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IOException(
                    "PowerShell failed (" + exitCode + "): "
                            + output.trim());
        }

        return output.trim();
    }

    private static String encodePowerShell(String script) {
        return Base64.getEncoder().encodeToString(
                script.getBytes(StandardCharsets.UTF_16LE));
    }

    private static Rectangle getAllScreenBounds() {
        Rectangle bounds = null;

        for (GraphicsDevice device :
                GraphicsEnvironment
                        .getLocalGraphicsEnvironment()
                        .getScreenDevices()) {

            Rectangle deviceBounds =
                    device.getDefaultConfiguration().getBounds();

            bounds = bounds == null
                    ? new Rectangle(deviceBounds)
                    : bounds.union(deviceBounds);
        }

        return bounds == null
                ? new Rectangle(0, 0, 1, 1)
                : bounds;
    }

    private record WindowInfo(
            long handle, long processId, long area) {
    }
}
