# Jenkins Shared Library

[Русская версия](README.ru.md)

## What is this and why?

This library contains reusable pipeline functions (steps) for Jenkins CI. It allows you to keep your `Jenkinsfile`s short, declarative, and focused solely on the specific project logic, hiding all the "dirty" work (Docker containers, caches, builder hacks) under the hood.

### Requirements and Plugins

For the library to work, you must have the following plugins installed in your Jenkins (Manage Jenkins -> Plugins):
1. **Pipeline Utility Steps** — critically important, used by the `declarativePipeline` function to read configuration from `pipeline-config.yaml` (`readYaml`).
2. **SSH Agent Plugin** — used by the `deployDockerCompose` function to connect to remote servers via SSH without passing passwords in plain text.
3. **Docker Pipeline** (and Docker Engine installed on the Jenkins agent itself) — for building images (`buildAndPushDockerImage`) and running isolated agents (`withNodeBuilder`, `withAndroidBuilder`).

### Jenkins Setup

#### 1. Connecting the library
To use this library in all your projects:
1. Go to **Manage Jenkins -> System**.
2. Find the **Global Pipeline Libraries** section.
3. Add a new library:
   - **Name**: `your-library-name` (this is the exact name used in the `@Library('your-library-name')` call).
   - **Default version**: `main` (or default branch/tag).
   - Select **Git** (or GitHub) and provide the library's repository URL.
   - Specify the Credentials (SSH key) that has read access to this repository.

#### 2. Setting up global variables and credentials
Pipelines rely on a number of global environment variables and credentials that need to be set once at the global Jenkins level.
1. **Global variables** (Manage Jenkins -> System -> Global properties -> Environment variables):
   - `SERVER_USER` — username on the target server (e.g., `deploy_user`).
   - `REGISTRY_IP` — IP address or domain of your Docker registry (e.g., `192.168.x.x`).
2. **SSH Keys** (Manage Jenkins -> Credentials):
   - Create an **SSH Username with private key** with an ID exactly matching the `SERVER_USER` value.

---

## Available Functions

To use the library functions, add an import at the very top of your `Jenkinsfile`:

```groovy
@Library('your-library-name') _
```

### `buildAndPushIfChanged`
Builds and pushes a Docker image only if it does not already exist in the registry (checks via `docker pull`). Useful for optimizing the build of toolchain images when the Dockerfile changes rarely.
**Usage example:**
```groovy
env.NODE_IMAGE_TAG = sh(script: 'sha1sum Dockerfile.build | cut -c1-12', returnStdout: true).trim()
buildAndPushIfChanged(env.NODE_IMAGE, env.NODE_IMAGE_TAG, 'Dockerfile.build', 'Node')
```

### `withNodeBuilder`
Runs the provided code block (Closure) inside an isolated Builder Docker container (Node.js). Automatically mounts the npm cache (via the `NPM_CACHE_VOLUME` volume) and runs the container as root to avoid permission issues.
**Usage example:**
```groovy
withNodeBuilder {
    sh 'npm ci --ignore-scripts'
    sh 'npm run build:web'
}
```

### `withAndroidBuilder`
Similar to `withNodeBuilder`, but runs the code block inside a Docker container for building Android apps. Mounts two caches at once: `NPM_CACHE_VOLUME` for npm and `GRADLE_CACHE_VOLUME` for Gradle.
**Usage example:**
```groovy
withAndroidBuilder {
    sh 'VITE_MODE=capacitor npm run build:cap'
    sh 'cd android && ./gradlew assembleRelease'
}
```

### `signAndroidApk`
Aligns (zipalign) and signs (apksigner) an Android APK. Accepts parameters as a Map.
**Usage example:**
```groovy
signAndroidApk(
    unsignedApk: 'android/app/build/outputs/apk/release/app-release-unsigned.apk',
    signedApk:   'android/app/build/outputs/apk/release/app-release.apk',
    keystore:    'keystore/release.keystore',
    storepass:   'password',
    keyalias:    'release',
    keypass:     'password'
)
```

### `deployDockerCompose`
Deploys an application via Docker Compose to a remote server over SSH, using the `sshagent` plugin. Pre-creates the directory on the server, copies the compose file there, and runs `docker compose up`. If `envVars` are provided, it generates a `.env.deploy` file on the remote server and runs compose with both `--env-file .env` and `--env-file .env.deploy`, ensuring the server's local `.env` is preserved.
**Usage example:**
```groovy
deployDockerCompose(
    credentialsId: 'my-ssh-key',
    user: 'deploy',
    host: '192.168.x.x',
    dir: '/opt/myapp',
    composeFile: 'compose.yml',
    envVars: [
        'DOCKER_IMAGE': '192.168.x.x:5050/myapp:latest'
    ]
)
```

### `buildCapacitorAndroid`
Executes the full Android APK build cycle for Capacitor projects (Vite build, platform init, Gradle compilation, and signing). Wrapped in `withAndroidBuilder`.
**Usage example:**
```groovy
buildCapacitorAndroid(
    buildScript: 'VITE_MODE=capacitor npm run build:cap',
    keystore: 'keystore/release.keystore',
    storepass: 'password',
    keyalias: 'release',
    keypass: 'password'
)
```

### `buildAndPushDockerImage`
Builds and immediately pushes a Docker image to the registry (including the `latest` tag).
**Usage example:**
```groovy
buildAndPushDockerImage(imageName: '192.168.x.x:5050/myapp', tag: 'v1.0.0')
```

### `cleanLocalDockerImages`
Removes local copies of Docker images to free up space on the Jenkins agent.
**Usage example (usually in a post { always { ... } } block):**
```groovy
cleanLocalDockerImages(imageName: '192.168.x.x:5050/myapp', tag: 'v1.0.0')
```

### `remoteDockerLogs`
Connects to a remote server over SSH and outputs the latest logs of a specified Docker container.
**Usage example:**
```groovy
remoteDockerLogs(containerName: 'my-bot', host: '192.168.x.x', user: 'deploy', credentialsId: 'my-ssh-key')
```

### `checkHttpEndpoint`
Sends an HTTP GET request to check service availability.
**Usage example:**
```groovy
checkHttpEndpoint(url: 'http://192.168.x.x:8000/api/health')
```

### `fixWorkspacePermissions`
Resets file permissions in the current Workspace to the user the Jenkins agent is running as.
**Usage example:**
```groovy
post { always { fixWorkspacePermissions() } }
```

---

## Pipeline Development Approaches

To support unique project requirements while maintaining standard pipelines, this library provides two main approaches for building your CI/CD: **Composition** (Manual) and **Dynamic** (Configuration-based).

### Approach 1: Composition (Manual Jenkinsfile)

If your project is highly non-standard, you can write a standard `Jenkinsfile` in your repository. Instead of starting from scratch, you construct your custom workflow using the low-level functions provided by this library.

**Key principles:**
1. **Build isolation**: Never build projects directly on the Jenkins host. Always use `withNodeBuilder` or `withAndroidBuilder`. This ensures the build doesn't depend on what is installed on the server.
2. **Caching**: The `with*Builder` wrappers automatically attach named volumes (`NPM_CACHE_VOLUME` and `GRADLE_CACHE_VOLUME`). This speeds up builds significantly.
3. **Security**: Hide your credentials. Pass only credentials IDs or paths to protected files.

**Example of a custom `Jenkinsfile` using library blocks:**

```groovy
@Library('your-library-name@main') _

pipeline {
    agent { label 'built-in' }
    
    stages {
        stage('Custom Build Engine') {
            steps {
                // Reuse the isolated environment wrapper
                withNodeBuilder {
                    sh 'npm run weird-build-process'
                }
            }
        }
        stage('Deploy') {
            steps {
                // Reuse the deployment function
                deployDockerCompose(
                    credentialsId: 'deploy_user', 
                    host: '10.0.0.1', 
                    dir: '/opt/app', 
                    composeFile: 'compose.yml'
                )
            }
        }
    }
}
```

---

### Approach 2: Dynamic Pipeline (The `declarativePipeline` standard)

The modern standard requires minimizing the `Jenkinsfile` in projects and moving logic into a universal `declarativePipeline()` step. Configuration is extracted to a `pipeline-config.yaml` file in the project root.

**`Jenkinsfile` (call):**
```groovy
@Library('your-library-name@main') _
declarativePipeline(agent: 'built-in')
```

#### Example `pipeline-config.yaml` configuration
**For a Docker-Compose project:**
```yaml
service_name: "myapp"
stack_type: "docker-compose"
target_cluster: "prod"
deploy:
  dir: "/opt/myapp"
healthcheck_url: "http://192.168.x.x:8125/health"
images:
  - name: "myapp-backend"
    context: "./backend"
migrations:
  service: "backend"
  delay: 20
containers:
  - "myapp_backend"
  - "myapp_db"
envVars:
  - SECRET_KEY
  - DATABASE_URL
```

**For a Capacitor project:**
```yaml
service_name: "my-hybrid-app"
stack_type: "capacitor"
target_cluster: "prod"
deploy:
  host: "10.0.0.1"
  dir: "/opt/myapp"
features:
  - typecheck
  - tests
```
*Note: Do not store secrets in `pipeline-config.yaml`. Credentials are automatically injected by Jenkins based on global variables.*

#### Extensibility and Inheritance (`custom_stages`)
If the standard `stack_type` template fits your project by 90%, you can use `custom_stages` in `pipeline-config.yaml` to inject, replace, or append specific steps without rewriting the pipeline.

Available insertion strategies:
* `insert_before: "Stage Name"` — Inject your stage before an existing one.
* `insert_after: "Stage Name"` — Inject your stage after an existing one.
* `replace: "Stage Name"` — Completely replace an existing standard stage.

**Example:**
```yaml
service_name: "myapp"
stack_type: "capacitor"
custom_stages:
  - name: "Custom Security Scan"
    insert_before: "Test"
    steps: |
      script {
          echo "Running custom security scan..."
          sh 'npm run scan'
      }
```

#### Server-Side Validation & Dry-Run
To avoid breaking builds deep in the CI process, `declarativePipeline` has built-in early validation. Right at the start of the build, Jenkins validates your `pipeline-config.yaml` against a JSON Schema. If the configuration is invalid, the build fails immediately.

If you want to test how your pipeline will look without actually running the build or deploying, run the job in Jenkins with the **`VALIDATE_ONLY`** parameter. The pipeline will print the generated `Jenkinsfile` into the console and successfully finish.

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details. You are free to use, modify, and distribute this code, provided that you include the copyright notice and a link back to this repository.
