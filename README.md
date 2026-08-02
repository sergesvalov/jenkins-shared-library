# Jenkins Shared Library

This library contains reusable pipeline functions (steps) for Jenkins CI.

[Русская версия](README.ru.md)

## Requirements and Plugins

For the library to work, you must have the following plugins installed in your Jenkins (Manage Jenkins -> Plugins):
1. **Pipeline Utility Steps** — critically important, used by the `declarativePipeline` function to read configuration from `pipeline-config.yaml` (`readYaml`).
2. **SSH Agent Plugin** — used by the `deployDockerCompose` function to connect to remote servers via SSH without passing passwords in plain text.
3. **Docker Pipeline** (and Docker Engine installed on the Jenkins agent itself) — for building images (`buildAndPushDockerImage`) and running isolated agents (`withNodeBuilder`, `withAndroidBuilder`).

## Jenkins Setup

### 1. Connecting the library
To use this library in all your projects:
1. Go to **Manage Jenkins -> System**.
2. Find the **Global Pipeline Libraries** section.
3. Add a new library:
   - **Name**: `your-library-name` (this is the exact name used in the `@Library('your-library-name')` call).
   - **Default version**: `main` (or default branch/tag).
   - Select **Git** (or GitHub) and provide the library's repository URL (e.g., `git@github.com:sergesvalov/jenkins-shared-library.git`).
   - Specify the Credentials (SSH key) that has read access to this repository.

### 2. Setting up global variables and credentials
Pipelines (especially `declarativePipeline`) rely on a number of global environment variables and credentials that need to be set once at the global Jenkins level.

1. **Global variables** (Manage Jenkins -> System -> Global properties -> Environment variables):
   - `SERVER_USER` — username on the target server (e.g., `deploy_user`). *Important: the pipeline expects that SSH credentials (key) with an ID exactly matching this value have been created in Jenkins!*
   - `REGISTRY_IP` — IP address or domain of your Docker registry (e.g., `192.168.x.x`). If not set, it falls back to `127.0.0.1`.
   - `PROD_SERVER_IP` (optional) — default production server IP address for the `prod` cluster.

2. **SSH Keys** (Manage Jenkins -> Credentials):
   - Create an **SSH Username with private key**.
   - **ID**: must match the `SERVER_USER` value (e.g., `deploy_user`).
   - Paste the private key that has access to the target servers where code will be deployed.

## How to connect

Add an import at the very top of your `Jenkinsfile`:

```groovy
@Library('your-library-name') _

pipeline {
    ...
}
```

*(The name `your-library-name` must be configured in your Jenkins global settings: Manage Jenkins -> System -> Global Pipeline Libraries).*

## Available Functions

Quick summary of all functions (steps) available in the library:
- `buildAndPushDockerImage` — Builds and pushes a Docker image to the registry.
- `buildAndPushIfChanged` — Builds a Docker image only if it doesn't already exist.
- `buildCapacitorAndroid` — Full Android APK build cycle for Capacitor projects.
- `capacitorPipeline` — Standard pipeline for building Capacitor applications.
- `checkHttpEndpoint` — Checks the availability of an HTTP endpoint.
- `cleanLocalDockerImages` — Removes local Docker images to free up space.
- `declarativePipeline` — Universal pipeline based on `pipeline-config.yaml`.
- `deployDockerCompose` — Deploys an application via Docker Compose over SSH.
- `dockerComposePipeline` — Standard pipeline for Docker Compose projects.
- `fixWorkspacePermissions` — Resets file permissions in the Jenkins workspace.
- `localDockerComposeDeploy` — Local deployment via Docker Compose.
- `packageGameArtifacts` — Archives game build artifacts (PC, Mac, Web).
- `remoteDockerLogs` — Fetches Docker container logs from a remote server.
- `runAlembicMigrations` — Runs database migrations (Alembic) inside Docker.
- `signAndroidApk` — Aligns (zipalign) and signs (apksigner) an Android APK.
- `withAndroidBuilder` — Runs code inside an isolated Android builder container.
- `withNodeBuilder` — Runs code inside an isolated Node.js builder container.

### `buildAndPushIfChanged`
Builds and pushes a Docker image only if it does not already exist in the registry (checks via `docker pull`). Useful for optimizing the build of toolchain images when the Dockerfile changes rarely.

**Parameters:**
* `image` (String) - Image name (without tag).
* `tag` (String) - Tag (e.g., hash of `Dockerfile`).
* `dockerfile` (String) - Path to `Dockerfile`.
* `label` (String) - Human-readable name for logs.

**Usage example:**
```groovy
env.NODE_IMAGE_TAG = sh(script: 'sha1sum Dockerfile.build | cut -c1-12', returnStdout: true).trim()
buildAndPushIfChanged(env.NODE_IMAGE, env.NODE_IMAGE_TAG, 'Dockerfile.build', 'Node')
```

### `withNodeBuilder`
Runs the provided code block (Closure) inside an isolated Builder Docker container (Node.js).
Automatically mounts the npm cache (via the `NPM_CACHE_VOLUME` volume) and runs the container as root to avoid permission issues with mounted directories.

**Expected environment variables:**
* `env.NODE_IMAGE` - base name of the builder image
* `env.NODE_IMAGE_TAG` - tag of the builder image
* `env.NPM_CACHE_VOLUME` - name of the Docker volume for npm caching

**Usage example:**
```groovy
withNodeBuilder {
    sh 'npm ci --ignore-scripts'
    sh 'npm run build:web'
}
```

### `withAndroidBuilder`
Similar to `withNodeBuilder`, but runs the code block inside a Docker container for building Android apps.
Mounts two caches at once: `NPM_CACHE_VOLUME` for npm and `GRADLE_CACHE_VOLUME` for Gradle, which is critical for rebuild speed. Runs as root.

**Expected environment variables:**
* `env.ANDROID_IMAGE` - base name of the Android builder image
* `env.ANDROID_IMAGE_TAG` - tag of the Android builder image
* `env.NPM_CACHE_VOLUME` - name of the Docker volume for npm caching
* `env.GRADLE_CACHE_VOLUME` - name of the Docker volume for Gradle caching

**Usage example:**
```groovy
withAndroidBuilder {
    sh 'VITE_MODE=capacitor npm run build:cap'
    sh 'npx cap sync android'
    sh 'cd android && ./gradlew assembleRelease'
}
```

### `signAndroidApk`
Aligns (zipalign) and signs (apksigner) an Android APK. Accepts parameters as a Map.
Can be used in any pipelines that build Android apps.

**Parameters:**
* `unsignedApk` - Path to the source unsigned APK (required)
* `signedApk` - Path where the signed APK will be saved (required)
* `keystore` - Path to the keystore file (.keystore/.jks) (required)
* `storepass` - Keystore password (required)
* `keyalias` - Key alias (required)
* `keypass` - Key password (required)
* `buildTools` - Build-tools version (default `35.0.0`)
* `zipalign` - Path to zipalign (default `/usr/local/bin/zipalign`)

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
Deploys an application via Docker Compose to a remote server over SSH, using the `sshagent` plugin.
Pre-creates the directory on the server, copies the compose file there, and runs `docker compose up`. If an `envVars` map is passed, it automatically generates and copies a `.env` file to the server.

**Parameters:**
* `credentialsId` - SSH key credentials ID in Jenkins (required)
* `user` - SSH user (required)
* `host` - IP or domain name of the server (required)
* `dir` - Directory on the server where the compose file will be copied and docker compose will run (required)
* `composeFile` - Path to the local compose file to send to the server (required)
* `envVars` - (Map) Environment variables that will be written to the `.env` file on the server (optional)

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
Executes the full Android APK build cycle for Capacitor projects (Vite build, platform init, `aapt2` fix for arm64, Gradle compilation, and signing). Wrapped in `withAndroidBuilder`.

**Parameters:**
* `buildScript` - Web part build script (default `npm run build:cap`)
* `keystore` - Path to keystore (default `keystore/release.keystore`)
* `storepass` - Keystore password
* `keyalias` - Key alias
* `keypass` - Key password

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
Builds and immediately pushes a Docker image to the registry (including the `latest` tag). Simplifies the standard Docker workflow.

**Parameters:**
* `imageName` - Image name (required)
* `tag` - Image tag (default `env.BUILD_NUMBER`)
* `context` - Build context (default `.`)
* `extraArgs` - Extra arguments for `docker build` (optional)

**Usage example:**
```groovy
buildAndPushDockerImage(imageName: '192.168.x.x:5050/myapp', tag: 'v1.0.0')
```

### `cleanLocalDockerImages`
Removes local copies of Docker images to free up space on the Jenkins agent. Calls `docker rmi`, ignoring errors.

**Parameters:**
* `imageName` - Image name (required)
* `tag` - Image tag (optional)

**Usage example (usually in a post { always { ... } } block):**
```groovy
cleanLocalDockerImages(imageName: '192.168.x.x:5050/myapp', tag: 'v1.0.0')
```

### `remoteDockerLogs`
Connects to a remote server over SSH and outputs the latest logs of a specified Docker container. Excellent for Health Check steps.

**Parameters:**
* `containerName` - Container name (required)
* `host` - Server IP (required)
* `user` - SSH user (required)
* `credentialsId` - SSH key credentials ID (required)
* `lines` - Number of log lines (default 30)

**Usage example:**
```groovy
remoteDockerLogs(
    containerName: 'my-bot',
    host: '192.168.x.x',
    user: 'deploy',
    credentialsId: 'my-ssh-key',
    lines: 50
)
```

### `checkHttpEndpoint`
Sends an HTTP GET request (via `curl` from the agent) to check service availability. Makes multiple attempts with a delay.

**Parameters:**
* `url` - Full URL to check (required)
* `retries` - Number of attempts (default 3)
* `sleepTime` - Delay between attempts in seconds (default 5)

**Usage example:**
```groovy
checkHttpEndpoint(url: 'http://192.168.x.x:8000/api/health')
```

### `fixWorkspacePermissions`
Resets file permissions in the current Workspace to the user the Jenkins agent is running as. Indispensable if you run tests or tools (playwright, pytest) in Docker containers that create files as the `root` user (which can cause "Permission denied" errors during workspace cleanup).

**Usage example:**
```groovy
post {
    always {
        fixWorkspacePermissions()
    }
}
```

## How to build pipelines with this library

Using a shared library allows you to keep your `Jenkinsfile`s short, declarative, and focused solely on the specific project logic, hiding all the "dirty" work (Docker containers, caches, builder hacks) under the hood.

### Basic pipeline template

Here is a typical skeleton of how we recommend structuring your build stages:

```groovy
@Library('your-library-name@main') _

pipeline {
    agent { label 'built-in' }
    
    options {
        skipDefaultCheckout()
    }
    
    environment {
        REGISTRY_IP = '192.168.x.x'
        // ... other variables
    }
    
    stages {
        stage('Checkout') {
            steps {
                checkout scm
                // Cleanup working directories
            }
        }
        
        stage('Build Toolchain') {
            steps {
                // Use buildAndPushIfChanged to avoid rebuilding
                // heavy images (Node, Android) if their Dockerfile hasn't changed
            }
        }
        
        stage('Dependencies & Tests') {
            steps {
                // Wrap steps in withNodeBuilder to run in an isolated
                // Node environment with npm caching
                withNodeBuilder {
                    sh 'npm ci'
                    sh 'npm run test'
                }
            }
        }
        
        stage('Build Web & Deploy') {
            steps {
                // Build static files
                withNodeBuilder {
                    sh 'npm run build:web'
                }
                
                // Deploy via ssh+docker-compose using the ready-made step
                deployDockerCompose(...)
            }
        }
        
        stage('Build Mobile') {
            steps {
                // Call a high-level step that does everything: Vite, Capacitor, Gradle, Zipalign, Apksigner
                buildCapacitorAndroid(...)
                
                // Archive artifacts
                archiveArtifacts artifacts: '**/*.apk'
            }
        }
    }
}
```

### Key principles
1. **Build isolation**: Never build projects directly on the Jenkins host. Always use `withNodeBuilder` or `withAndroidBuilder`. This ensures the build doesn't depend on what is installed on the server.
2. **Caching**: The `with*Builder` wrappers automatically attach named volumes (`NPM_CACHE_VOLUME` and `GRADLE_CACHE_VOLUME`). This speeds up builds by dozens of times compared to a clean package download on every run.
3. **Security**: Hide your credentials. Pass only credentials IDs (like in `deployDockerCompose`) or paths to protected files (`buildCapacitorAndroid`).
4. **DRY (Don't Repeat Yourself)**: If you see the same 5-10 line bash script moving from project to project (like it was with the `aapt2` arm64 hack), move it into a `vars/stepName.groovy` file in this library.

### Using `declarativePipeline` (New standard)

The new standard requires minimizing the `Jenkinsfile` in projects and moving logic into a universal `declarativePipeline()` step. Configuration is extracted to a `pipeline-config.yaml` file in the project root.

Example `Jenkinsfile` (call):
```groovy
@Library('your-library-name@main') _
declarativePipeline(agent: 'built-in')
```
*(The `agent` parameter is required, you can specify any available Jenkins node label, e.g., `agent: 'my-custom-node'`)*

#### Example `pipeline-config.yaml` configuration for a multi-container project
Placed in the root directory of your project. Allows building multiple images (backend, frontend) and automatically running DB migrations.

```yaml
service_name: "myapp"
stack_type: "docker-compose"
target_cluster: "prod"
deploy:
  dir: "/opt/myapp" # Directory on the target server for deploy
images:
  - name: "myapp-backend"
    context: "./backend"
  - name: "myapp-frontend"
    context: "./frontend"
migrations:
  service: "backend"
  delay: 20 # Delay before running alembic upgrade head
containers:
  - "myapp_backend"
  - "myapp_frontend"
  - "myapp_db"
```

#### Example `pipeline-config.yaml` configuration for a mobile/web project (`stack_type: "capacitor"`)
Placed in the root directory of your project.
```yaml
service_name: "my-hybrid-app"
stack_type: "capacitor"
target_cluster: "prod"
deploy:
  # Target server IP address or domain for deploy
  # (Real address is hidden here for security purposes)
  host: "<SERVER_IP_ADDRESS>"
  dir: "/opt/myapp"
  web_port: 7979
features:
  - typecheck
  - tests
  - e2e
```

**About data security:**
*The `pipeline-config.yaml` configuration file must not contain any secret keys, passwords, or credentials. Credentials and keys for accessing servers or Android certificates are pulled automatically by Jenkins from its secure storage (Jenkins Credentials) via global variables (e.g., `SSH_CREDS_ID`, `SERVER_USER`). The config file only contains the general deploy structure.*

### Server-Side Validation and Pipeline Generation (New Approach)

To avoid breaking builds deep in the CI process and to support highly customized stages ("escape hatches"), `declarativePipeline` now has built-in, early server-side validation and generation.

Your `Jenkinsfile` remains a simple one-liner (`declarativePipeline(agent: 'built-in')`), but under the hood Jenkins will do the following:

1. **Early Validation**: Right at the start of the build, Jenkins pulls the generator script from the library's `resources/` folder and validates your `pipeline-config.yaml` against a JSON Schema. If the configuration is invalid (e.g. typos, missing required fields), the build fails immediately on the first seconds.
2. **VALIDATE_ONLY Mode (Dry-Run)**: If you want to test how your pipeline will look without actually running the build or deploying, you can run the job in Jenkins and check the **`VALIDATE_ONLY`** parameter. The pipeline will validate the configuration, print the generated `Jenkinsfile` (with all stages and bash scripts) into the Jenkins console, and successfully finish.
3. **Escape Hatches**: Add `custom_stages` in your `pipeline-config.yaml` to inject specific steps into the dynamically generated pipeline. This ensures "snowflake" repositories can still use the standard template without forking it.

**Example `pipeline-config.yaml` with a custom stage:**
```yaml
service_name: "myapp"
stack_type: "capacitor"
custom_stages:
  - name: "Security Scan"
    insert_before: "Test"
    steps: |
      script {
          echo "Running custom security scan..."
          // sh 'npm run scan'
      }
```
