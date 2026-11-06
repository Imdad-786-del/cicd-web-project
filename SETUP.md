# Pipeline setup

## 1. Run it

```bash
javac PipelineRunner.java

export PROJECT_DIR=webapp   # default; folder containing pom.xml
export SONAR_HOST_URL=http://localhost:9000
export SONAR_TOKEN=xxxxxxxx
export NEXUS_URL=http://localhost:8081/repository/maven-releases/
export NEXUS_USER=admin
export NEXUS_PASS=secret
export TOMCAT_URL=http://localhost:8080
export TOMCAT_USER=deployer
export TOMCAT_PASS=secret
export TOMCAT_CONTEXT_PATH=/webapp

java PipelineRunner
```

Output: `webapp/target/webapp-release.zip` containing the `.war`.

## 2. Requirements in your Maven project (pom.xml)

```xml
<packaging>war</packaging>

<build>
  <plugins>
    <plugin>
      <groupId>org.sonarsource.scanner.maven</groupId>
      <artifactId>sonar-maven-plugin</artifactId>
      <version>4.0.0.4121</version>
    </plugin>
  </plugins>
</build>
```

- Use a **release** version (e.g. `1.0.0`) if NEXUS_URL points to a releases repo;
  use `1.0.0-SNAPSHOT` with a snapshots repo URL.
- Nexus rejects re-uploading the same release version unless the repo's
  deployment policy is "Allow redeploy".
- With maven-deploy-plugin 2.x, change the Java argument to
  `nexus::default::<url>`; 3.x uses `nexus::<url>` (as in the code).

## 3. Tomcat Manager access

Add to `conf/tomcat-users.xml`:

```xml
<role rolename="manager-script"/>
<user username="deployer" password="secret" roles="manager-script"/>
```

The `manager-script` role is required for the `/manager/text` API.
