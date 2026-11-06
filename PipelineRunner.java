import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.*;

/**
 * CI/CD pipeline in plain Java (Java 11+):
 *   1. Maven clean verify         (build + unit tests)
 *   2. SonarQube analysis         (mvn sonar:sonar, waits for quality gate)
 *   3. Nexus upload               (mvn deploy -> Nexus repository)
 *   4. Tomcat deployment          (Tomcat Manager text API)
 *   5. Package the .war in a .zip (target/<name>-release.zip)
 *
 * Run:  javac PipelineRunner.java && java PipelineRunner
 *
 * Configuration comes from environment variables (see env() calls below).
 */
public class PipelineRunner {

    // ---------- configuration ----------
    static final Path PROJECT_DIR   = Paths.get(env("PROJECT_DIR", "webapp")).toAbsolutePath();
    static final String SONAR_URL   = env("SONAR_HOST_URL", "http://localhost:9000");
    static final String SONAR_TOKEN = env("SONAR_TOKEN", null);          // required
    static final String NEXUS_URL   = env("NEXUS_URL", "http://localhost:8081/repository/maven-releases/");
    static final String NEXUS_USER  = env("NEXUS_USER", null);           // required
    static final String NEXUS_PASS  = env("NEXUS_PASS", null);           // required
    static final String TOMCAT_URL  = env("TOMCAT_URL", "http://localhost:8080");
    static final String TOMCAT_USER = env("TOMCAT_USER", null);          // required
    static final String TOMCAT_PASS = env("TOMCAT_PASS", null);          // required
    static final String CONTEXT     = env("TOMCAT_CONTEXT_PATH", "/webapp");

    public static void main(String[] args) {
        Path tempSettings = null;
        try {
            require("SONAR_TOKEN", SONAR_TOKEN);
            require("NEXUS_USER", NEXUS_USER);
            require("NEXUS_PASS", NEXUS_PASS);
            require("TOMCAT_USER", TOMCAT_USER);
            require("TOMCAT_PASS", TOMCAT_PASS);

            String mvn = isWindows() ? "mvn.cmd" : "mvn";

            step("1/5 Maven build + tests");
            run(mvn, "-B", "clean", "verify");

            step("2/5 SonarQube analysis");
            run(mvn, "-B", "sonar:sonar",
                    "-Dsonar.host.url=" + SONAR_URL,
                    "-Dsonar.token=" + SONAR_TOKEN,
                    "-Dsonar.qualitygate.wait=true");   // fails the pipeline if the gate fails

            step("3/5 Publish to Nexus");
            tempSettings = writeTempSettings();
            run(mvn, "-B", "-s", tempSettings.toString(), "deploy", "-DskipTests",
                    "-DaltDeploymentRepository=nexus::" + NEXUS_URL);

            Path war = findWar();
            System.out.println("WAR built: " + war);

            step("4/5 Deploy to Tomcat");
            deployToTomcat(war);

            step("5/5 Create zip");
            Path zip = zipWar(war);
            System.out.println("\nPipeline SUCCESS. Zip: " + zip);

        } catch (Exception e) {
            System.err.println("\nPipeline FAILED: " + e.getMessage());
            System.exit(1);
        } finally {
            if (tempSettings != null) {
                try { Files.deleteIfExists(tempSettings); } catch (IOException ignored) { }
            }
        }
    }

    // ---------- helpers ----------

    /** Runs a command in the project directory, streaming output; throws on non-zero exit. */
    static void run(String... cmd) throws Exception {
        System.out.println("$ " + maskSecrets(String.join(" ", cmd)));
        Process p = new ProcessBuilder(cmd)
                .directory(PROJECT_DIR.toFile())
                .redirectErrorStream(true)
                .inheritIO()
                .start();
        int code = p.waitFor();
        if (code != 0) throw new RuntimeException("Command failed (exit " + code + "): " + cmd[Math.min(cmd.length - 1, 1)]);
    }

    /** Temporary Maven settings.xml carrying the Nexus credentials (server id "nexus"). */
    static Path writeTempSettings() throws IOException {
        String xml = "<settings><servers><server>"
                + "<id>nexus</id>"
                + "<username>" + xmlEscape(NEXUS_USER) + "</username>"
                + "<password>" + xmlEscape(NEXUS_PASS) + "</password>"
                + "</server></servers></settings>";
        Path f = Files.createTempFile("mvn-settings-", ".xml");
        Files.writeString(f, xml, StandardCharsets.UTF_8);
        return f;
    }

    static Path findWar() throws IOException {
        try (Stream<Path> s = Files.list(PROJECT_DIR.resolve("target"))) {
            return s.filter(p -> p.getFileName().toString().endsWith(".war"))
                    .findFirst()
                    .orElseThrow(() -> new FileNotFoundException(
                            "No .war in target/. Check <packaging>war</packaging> in pom.xml"));
        }
    }

    /** Uses the Tomcat Manager text interface: PUT /manager/text/deploy?path=/ctx&update=true */
    static void deployToTomcat(Path war) throws Exception {
        String auth = Base64.getEncoder().encodeToString(
                (TOMCAT_USER + ":" + TOMCAT_PASS).getBytes(StandardCharsets.UTF_8));
        URI uri = URI.create(TOMCAT_URL + "/manager/text/deploy?path=" + CONTEXT + "&update=true");

        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Authorization", "Basic " + auth)
                .PUT(HttpRequest.BodyPublishers.ofFile(war))
                .build();

        HttpResponse<String> res = HttpClient.newHttpClient()
                .send(req, HttpResponse.BodyHandlers.ofString());

        System.out.println("Tomcat: " + res.body().trim());
        if (res.statusCode() != 200 || !res.body().startsWith("OK"))
            throw new RuntimeException("Tomcat deploy failed (HTTP " + res.statusCode() + ")");
    }

    /** Puts the .war inside target/<warname>-release.zip */
    static Path zipWar(Path war) throws IOException {
        String base = war.getFileName().toString().replaceAll("\\.war$", "");
        Path zip = war.getParent().resolve(base + "-release.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry(war.getFileName().toString()));
            Files.copy(war, zos);
            zos.closeEntry();
        }
        return zip;
    }

    static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    static void require(String name, String value) {
        if (value == null) throw new IllegalStateException("Missing environment variable: " + name);
    }

    static boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("win"); }

    static void step(String title) { System.out.println("\n===== " + title + " ====="); }

    static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String maskSecrets(String s) {
        return s.replace(SONAR_TOKEN == null ? "\0" : SONAR_TOKEN, "****");
    }
}
