const fs = require('fs');
const path = require('path');

// Попытка загрузить зависимости.
let yaml, Ajv, diff;
try {
  yaml = require('yaml');
  const AjvClass = require('ajv');
  Ajv = new AjvClass();
} catch (e) {
  console.error("Please run 'npm install -D yaml ajv' in your project first.");
  process.exit(1);
}

try {
  diff = require('diff');
} catch (e) {} // diff is optional

const args = process.argv.slice(2);
const isDryRun = args.includes('--dry-run');
const configPath = args.find(a => !a.startsWith('--')) || 'pipeline-config.yaml';

if (!fs.existsSync(configPath)) {
  console.error(`Config file not found: ${configPath}`);
  process.exit(1);
}

const configObj = yaml.parse(fs.readFileSync(configPath, 'utf8'));

// Валидация по схеме
const schemaPath = path.join(process.cwd(), 'pipeline-config.schema.json');
const schema = JSON.parse(fs.readFileSync(schemaPath, 'utf8'));
const validate = Ajv.compile(schema);
const valid = validate(configObj);

if (!valid) {
  console.error("Invalid pipeline-config.yaml:");
  console.error(validate.errors);
  process.exit(1); // Роняем коммит!
}

// Построение стадий пайплайна
let stages = [];

if (configObj.stack_type === 'capacitor' || configObj.stack_type === 'node') {
    stages.push({
        name: 'Source Checkout & Setup',
        body: `        stage('Source Checkout & Setup') {
            steps {
                script {
                    sh 'docker run --rm -v $(pwd):/workspace alpine chown -R $(id -u):$(id -g) /workspace || true'
                    checkout scm
                    sh 'docker run --rm -u root -v "$WORKSPACE:$WORKSPACE" -w "$WORKSPACE" node:22-bookworm-slim rm -rf dist release android playwright-report test-results || true'
                    
                    env.SERVICE_NAME = "${configObj.service_name}"
                    env.STACK_TYPE = "${configObj.stack_type}"
                    
                    env.HAS_FEATURE_TYPECHECK = "${(configObj.features || []).includes('typecheck')}"
                    env.HAS_FEATURE_TESTS = "${(configObj.features || []).includes('tests')}"
                    env.HAS_FEATURE_E2E = "${(configObj.features || []).includes('e2e')}"
                    
                    def gitCommit = sh(script: 'git rev-parse HEAD || echo "local"', returnStdout: true).trim()
                    env.DEPLOY_SERVER_IP = "${configObj.target_cluster}" == 'prod' ? env.PROD_SERVER_IP : (env.STAGING_SERVER_IP ?: '127.0.0.1')
                    env.REGISTRY_IP = env.REGISTRY_IP ?: '127.0.0.1'
                    env.SSH_CREDS_ID = env.SERVER_USER
                    
                    env.NODE_IMAGE = "\${env.REGISTRY_IP}:5050/${configObj.service_name}-builder"
                    env.ANDROID_IMAGE = "\${env.REGISTRY_IP}:5050/${configObj.service_name}-android"
                    env.WEB_IMAGE = "\${env.REGISTRY_IP}:5050/${configObj.service_name}-web"
                    
                    env.NPM_CACHE_VOLUME = "${configObj.service_name}-npm-cache"
                    env.GRADLE_CACHE_VOLUME = "${configObj.service_name}-gradle-cache"
                    env.BUILD_TAG = "\${env.BUILD_NUMBER}-\${gitCommit.take(7)}"
                    
                    ${configObj.deploy ? `env.DEPLOY_TARGET_HOST = "${configObj.deploy.host || ''}" ?: env.DEPLOY_SERVER_IP
                    env.DEPLOY_TARGET_DIR = "${configObj.deploy.dir || ''}" ?: "~/${configObj.service_name}"
                    env.DEPLOY_TARGET_PORT = "${configObj.deploy.web_port || ''}"` : ''}
                }
            }
        }`
    });

    stages.push({
        name: 'Build Toolchain Images',
        body: `        stage('Build Toolchain Images') {
            steps {
                script {
                    env.NODE_IMAGE_TAG = sh(script: 'sha1sum Dockerfile.build | cut -c1-12', returnStdout: true).trim()
                    if (params.FORCE_REBUILD_IMAGES) {
                        buildAndPushDockerImage(imageName: env.NODE_IMAGE, tag: env.NODE_IMAGE_TAG, context: '.', extraArgs: '-f Dockerfile.build')
                    } else {
                        buildAndPushIfChanged(env.NODE_IMAGE, env.NODE_IMAGE_TAG, 'Dockerfile.build', 'Node')
                    }

                    if (params.BUILD_ANDROID) {
                        env.ANDROID_IMAGE_TAG = sh(script: 'sha1sum Dockerfile.android | cut -c1-12', returnStdout: true).trim()
                        if (params.FORCE_REBUILD_IMAGES) {
                            buildAndPushDockerImage(imageName: env.ANDROID_IMAGE, tag: env.ANDROID_IMAGE_TAG, context: '.', extraArgs: '-f Dockerfile.android')
                        } else {
                            buildAndPushIfChanged(env.ANDROID_IMAGE, env.ANDROID_IMAGE_TAG, 'Dockerfile.android', 'Android')
                        }
                    }
                }
            }
        }`
    });

    stages.push({
        name: 'Install Dependencies',
        body: `        stage('Install Dependencies') {
            steps {
                script {
                    echo "📦 npm ci in workspace..."
                    withNodeBuilder {
                        sh 'npm install --ignore-scripts'
                    }
                }
            }
        }`
    });

    stages.push({
        name: 'TypeScript Check',
        body: `        stage('TypeScript Check') {
            when { 
                expression { return env.HAS_FEATURE_TYPECHECK == 'true' && !params.SKIP_TYPECHECK } 
            }
            steps {
                script {
                    echo "🔍 Running tsc --noEmit..."
                    withNodeBuilder {
                        sh './node_modules/.bin/tsc --noEmit'
                    }
                }
            }
        }`
    });

    stages.push({
        name: 'Test',
        body: `        stage('Test') {
            when { 
                expression { return (env.HAS_FEATURE_TESTS == 'true' || env.HAS_FEATURE_E2E == 'true') && !params.SKIP_TESTS } 
            }
            steps {
                script {
                    if (env.HAS_FEATURE_TESTS == 'true') {
                        echo "🧪 Running Unit tests (Vitest)..."
                        withNodeBuilder {
                            sh 'npm run test'
                        }
                    }

                    if (env.HAS_FEATURE_E2E == 'true') {
                        echo "🎭 Running E2E tests (Playwright)..."
                        docker.image('mcr.microsoft.com/playwright:v1.49.1-jammy').inside("-u root -v \${env.NPM_CACHE_VOLUME}:/tmp/.npm --shm-size=1gb") {
                            sh 'npm install --ignore-scripts'
                            sh 'npx playwright install chromium'
                            sh 'npm run test:e2e'
                        }
                    }
                }
            }
        }`
    });

    stages.push({
        name: 'Build: Web (Capacitor)',
        body: `        stage('Build: Web (Capacitor)') {
            when { 
                expression { return params.BUILD_WEB } 
            }
            steps {
                script {
                    echo "🌐 Vite web build..."
                    withNodeBuilder {
                        sh 'VITE_MODE=web npm run build:web'
                    }
                    
                    echo "🐳 Building Nginx image with dist/ inside..."
                    sh "docker build -t \${env.WEB_IMAGE}:\${env.BUILD_TAG} -t \${env.WEB_IMAGE}:latest -f Dockerfile.nginx ."
                    sh "docker push \${env.WEB_IMAGE}:\${env.BUILD_TAG}"
                    sh "docker push \${env.WEB_IMAGE}:latest"

                    stash name: 'compose', includes: 'compose.yml'
                }
            }
        }`
    });

    stages.push({
        name: 'Build: Android (.apk)',
        body: `        stage('Build: Android (.apk)') {
            when { 
                expression { return params.BUILD_ANDROID } 
            }
            steps {
                script {
                    echo "🤖 Capacitor → Gradle → Android APK..."
                    buildCapacitorAndroid(
                        buildScript: 'VITE_MODE=capacitor npm run build:cap',
                        keystore: 'keystore/release.keystore',
                        storepass: 'password',
                        keyalias: 'release',
                        keypass: 'password'
                    )
                    sh 'find android/app/build/outputs/apk -name "*.apk" | head -5'
                    archiveArtifacts artifacts: 'android/app/build/outputs/apk/**/*.apk', fingerprint: true
                }
            }
        }`
    });

    stages.push({
        name: 'Deploy',
        body: `        stage('Deploy') {
            when {
                anyOf {
                    branch 'main'
                    expression { return params.FORCE_DEPLOY }
                }
                expression { return params.BUILD_WEB }
            }
            steps {
                script {
                    echo "🚀 Docker deploy to \${env.DEPLOY_TARGET_HOST} ..."
                    unstash 'compose'
                    deployDockerCompose(
                        credentialsId: env.SSH_CREDS_ID,
                        user: env.SERVER_USER,
                        host: env.DEPLOY_TARGET_HOST,
                        dir: env.DEPLOY_TARGET_DIR,
                        composeFile: 'compose.yml'
                    )
                    if (env.DEPLOY_TARGET_PORT) {
                        echo "✅ Available at: http://\${env.DEPLOY_TARGET_HOST}:\${env.DEPLOY_TARGET_PORT}"
                    }
                }
            }
        }`
    });
} else if (configObj.stack_type === 'docker-compose') {
    stages.push({
        name: 'Build',
        body: `        stage('Build Docker Compose') { steps { echo "Building docker-compose stack..." } }`
    });
}

// Врезка кастомных стадий (Аварийный люк)
if (configObj.custom_stages) {
    configObj.custom_stages.forEach(cs => {
        const customStageBody = `        stage('${cs.name}') {
            steps {
                ${cs.steps.trim().replace(/\n/g, '\n                ')}
            }
        }`;
        
        if (cs.insert_before) {
            const idx = stages.findIndex(s => s.name === cs.insert_before);
            if (idx !== -1) stages.splice(idx, 0, { name: cs.name, body: customStageBody });
        } else if (cs.insert_after) {
            const idx = stages.findIndex(s => s.name === cs.insert_after);
            if (idx !== -1) stages.splice(idx + 1, 0, { name: cs.name, body: customStageBody });
        } else if (cs.replace) {
            const idx = stages.findIndex(s => s.name === cs.replace);
            if (idx !== -1) stages[idx] = { name: cs.name, body: customStageBody };
        } else {
            stages.push({ name: cs.name, body: customStageBody });
        }
    });
}

const pipelineHeader = `// AUTO-GENERATED BY jenkins-shared-library/bin/generate-pipeline.js
// DO NOT EDIT BY HAND
// Config: ${configPath}

@Library('mylib@main') _

pipeline {
    agent { label "built-in" }
    options {
        skipDefaultCheckout()
    }
    parameters {
        booleanParam(name: 'SKIP_TYPECHECK',       defaultValue: false, description: 'Skip TypeScript check (if applicable)')
        booleanParam(name: 'SKIP_TESTS',           defaultValue: false, description: 'Skip all tests (Unit and E2E)')
        booleanParam(name: 'BUILD_WEB',             defaultValue: true,  description: 'Build web version and deploy')
        booleanParam(name: 'BUILD_ANDROID',         defaultValue: true,  description: 'Build Android .apk (if applicable)')
        booleanParam(name: 'FORCE_DEPLOY',          defaultValue: false, description: 'Deploy web even if not main branch')
        booleanParam(name: 'FORCE_REBUILD_IMAGES',  defaultValue: false, description: 'Rebuild toolchain images even if Dockerfile unchanged')
    }
    stages {
`;

const pipelineFooter = `    }
    post {
        always {
            script {
                sh 'docker image prune -f || true'
                sh "docker run --rm -v \\$(pwd):/workspace alpine chown -R \\$(id -u):\\$(id -g) /workspace || true"
            }
        }
        success {
            echo "✅ ${configObj.service_name} built successfully! Build: \${env.BUILD_TAG}"
        }
        failure {
            echo "❌ ${configObj.service_name}: build failed."
        }
    }
}
`;

const finalJenkinsfile = pipelineHeader + stages.map(s => s.body).join('\n\n') + '\n' + pipelineFooter;

// Записываем в Jenkinsfile.generated чтобы не ломать текущий пайплайн
const targetJenkinsfile = 'Jenkinsfile.generated';
const currentJenkinsfile = fs.existsSync(targetJenkinsfile) ? fs.readFileSync(targetJenkinsfile, 'utf8') : '';

if (isDryRun) {
    console.log("=== DRY RUN ===");
    if (diff && currentJenkinsfile) {
        const patch = diff.createPatch(targetJenkinsfile, currentJenkinsfile, finalJenkinsfile);
        console.log(patch);
    } else {
        console.log("Generated Jenkinsfile:");
        console.log(finalJenkinsfile);
    }
} else {
    fs.writeFileSync(targetJenkinsfile, finalJenkinsfile);
    console.log(`Successfully generated ${targetJenkinsfile} from ${configPath}`);
}
