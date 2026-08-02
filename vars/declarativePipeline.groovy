def call() {
    pipeline {
        agent { label 'built-in' }

        options {
            skipDefaultCheckout()
        }

        parameters {
            booleanParam(name: 'SKIP_TYPECHECK',       defaultValue: false, description: 'Skip TypeScript check (if applicable)')
            booleanParam(name: 'BUILD_WEB',             defaultValue: true,  description: 'Build web version and deploy')
            booleanParam(name: 'BUILD_ANDROID',         defaultValue: true,  description: 'Build Android .apk (if applicable)')
            booleanParam(name: 'FORCE_DEPLOY',          defaultValue: false, description: 'Deploy web even if not main branch')
            booleanParam(name: 'FORCE_REBUILD_IMAGES',  defaultValue: false, description: 'Rebuild toolchain images (Node/Android) even if Dockerfile unchanged')
        }

        stages {
            stage('Source Checkout & Config') {
                steps {
                    script {
                        // Clean up root files that might have been left by previous runs
                        sh 'docker run --rm -v $(pwd):/workspace alpine chown -R $(id -u):$(id -g) /workspace || true'
                        
                        echo "Checking out source code..."
                        checkout scm
                        
                        // Deep cleanup for workspace (especially for capacitor projects)
                        sh 'docker run --rm -u root -v "$WORKSPACE:$WORKSPACE" -w "$WORKSPACE" node:22-bookworm-slim rm -rf dist release android playwright-report test-results || true'
                        
                        echo "Reading pipeline-config.yaml..."
                        def config = readYaml file: 'pipeline-config.yaml'
                        
                        env.SERVICE_NAME = config.service_name
                        env.STACK_TYPE = config.stack_type
                        
                        def featuresList = config.features ?: []
                        env.HAS_FEATURE_TYPECHECK = featuresList.contains('typecheck') ? 'true' : 'false'
                        env.HAS_FEATURE_TESTS = featuresList.contains('tests') ? 'true' : 'false'
                        env.HAS_FEATURE_E2E = featuresList.contains('e2e') ? 'true' : 'false'
                        
                        env.DEPLOY_SERVER_IP = config.target_cluster == 'prod' ? env.PROD_SERVER_IP : (env.STAGING_SERVER_IP ?: '127.0.0.1')
                        
                        env.REGISTRY_IP = env.REGISTRY_IP ?: '127.0.0.1'
                        env.SSH_CREDS_ID = env.SERVER_USER
                        env.SERVER_USER = env.SERVER_USER
                        
                        env.DOCKER_IMAGE = "${env.REGISTRY_IP}:5050/${config.service_name}"
                        
                        env.NODE_IMAGE = "${env.REGISTRY_IP}:5050/${config.service_name}-builder"
                        env.ANDROID_IMAGE = "${env.REGISTRY_IP}:5050/${config.service_name}-android"
                        env.WEB_IMAGE = "${env.REGISTRY_IP}:5050/${config.service_name}-web"
                        
                        env.NPM_CACHE_VOLUME = "${config.service_name}-npm-cache"
                        env.GRADLE_CACHE_VOLUME = "${config.service_name}-gradle-cache"
                        
                        env.BUILD_TAG = "${env.BUILD_NUMBER}-${env.GIT_COMMIT ? env.GIT_COMMIT.take(7) : 'local'}"
                        
                        env.HOST_PORT = config.ports?.host ? config.ports.host.toString() : ""
                        
                        if (config.deploy) {
                            env.DEPLOY_TARGET_HOST = config.deploy.host ?: env.DEPLOY_SERVER_IP
                            env.DEPLOY_TARGET_DIR = config.deploy.dir ?: "~/${config.service_name}"
                            env.DEPLOY_TARGET_PORT = config.deploy.web_port ? config.deploy.web_port.toString() : ""
                        } else {
                            env.DEPLOY_TARGET_HOST = env.DEPLOY_SERVER_IP
                            env.DEPLOY_TARGET_DIR = "~/${env.SERVICE_NAME}"
                            env.DEPLOY_TARGET_PORT = env.HOST_PORT
                        }
                        
                        env.CONTAINERS = (config.containers ?: []).join(' ')
                    }
                }
            }

            stage('Build Toolchain Images') {
                when { expression { return env.STACK_TYPE == 'capacitor' || env.STACK_TYPE == 'node' } }
                steps {
                    script {
                        env.NODE_IMAGE_TAG = sh(script: 'sha1sum Dockerfile.build | cut -c1-12', returnStdout: true).trim()
                        if (params.FORCE_REBUILD_IMAGES) {
                            buildAndPushDockerImage(imageName: env.NODE_IMAGE, tag: env.NODE_IMAGE_TAG, context: '.', extraArgs: '-f Dockerfile.build')
                        } else {
                            buildAndPushIfChanged(env.NODE_IMAGE, env.NODE_IMAGE_TAG, 'Dockerfile.build', 'Node')
                        }

                        if (params.BUILD_ANDROID && env.STACK_TYPE == 'capacitor') {
                            env.ANDROID_IMAGE_TAG = sh(script: 'sha1sum Dockerfile.android | cut -c1-12', returnStdout: true).trim()
                            if (params.FORCE_REBUILD_IMAGES) {
                                buildAndPushDockerImage(imageName: env.ANDROID_IMAGE, tag: env.ANDROID_IMAGE_TAG, context: '.', extraArgs: '-f Dockerfile.android')
                            } else {
                                buildAndPushIfChanged(env.ANDROID_IMAGE, env.ANDROID_IMAGE_TAG, 'Dockerfile.android', 'Android')
                            }
                        }
                    }
                }
            }

            stage('Install Dependencies') {
                when { expression { return env.STACK_TYPE == 'capacitor' || env.STACK_TYPE == 'node' } }
                steps {
                    script {
                        echo "📦 npm ci в workspace..."
                        withNodeBuilder {
                            sh 'npm install --ignore-scripts'
                        }
                    }
                }
            }

            stage('TypeScript Check') {
                when { 
                    expression { return env.HAS_FEATURE_TYPECHECK == 'true' && !params.SKIP_TYPECHECK } 
                }
                steps {
                    script {
                        echo "🔍 Запуск tsc --noEmit..."
                        withNodeBuilder {
                            sh './node_modules/.bin/tsc --noEmit'
                        }
                    }
                }
            }

            stage('Test') {
                when { 
                    expression { return env.HAS_FEATURE_TESTS == 'true' || env.HAS_FEATURE_E2E == 'true' } 
                }
                steps {
                    script {
                        if (env.HAS_FEATURE_TESTS == 'true') {
                            echo "🧪 Запуск Unit-тестов (Vitest)..."
                            withNodeBuilder {
                                sh 'npm run test'
                            }
                        }

                        if (env.HAS_FEATURE_E2E == 'true') {
                            echo "🎭 Запуск E2E-тестов (Playwright)..."
                            docker.image('mcr.microsoft.com/playwright:v1.49.1-jammy').inside("-u root -v ${env.NPM_CACHE_VOLUME}:/tmp/.npm --shm-size=1gb") {
                                sh 'npm install --ignore-scripts'
                                sh 'npx playwright install chromium'
                                sh 'npm run test:e2e'
                            }
                        }
                    }
                }
            }

            stage('Build: Web (Capacitor)') {
                when { 
                    expression { return env.STACK_TYPE == 'capacitor' && params.BUILD_WEB } 
                }
                steps {
                    script {
                        echo "🌐 Vite web-билд..."
                        withNodeBuilder {
                            sh 'VITE_MODE=web npm run build:web'
                        }
                        
                        echo "🐳 Сборка Nginx-образа с dist/ внутри..."
                        sh "docker build -t ${env.WEB_IMAGE}:${env.BUILD_TAG} -t ${env.WEB_IMAGE}:latest -f Dockerfile.nginx ."
                        sh "docker push ${env.WEB_IMAGE}:${env.BUILD_TAG}"
                        sh "docker push ${env.WEB_IMAGE}:latest"

                        stash name: 'compose', includes: 'compose.yml'
                    }
                }
            }

            stage('Build: Android (.apk)') {
                when { 
                    expression { return env.STACK_TYPE == 'capacitor' && params.BUILD_ANDROID } 
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
            }

            stage('Build & Push (Docker Compose)') {
                when { expression { return env.STACK_TYPE == 'docker-compose' } }
                steps {
                    script {
                        buildAndPushDockerImage(imageName: env.DOCKER_IMAGE, tag: env.BUILD_TAG)
                        stash name: 'compose', includes: 'docker-compose.yml'
                    }
                }
            }

            stage('Deploy') {
                when {
                    anyOf {
                        branch 'main'
                        expression { return params.FORCE_DEPLOY }
                        expression { return env.STACK_TYPE == 'docker-compose' }
                    }
                    expression { return env.STACK_TYPE == 'docker-compose' || (env.STACK_TYPE == 'capacitor' && params.BUILD_WEB) }
                }
                steps {
                    script {
                        echo "🚀 Docker-деплой на ${env.DEPLOY_TARGET_HOST} ..."
                        
                        if (env.STACK_TYPE == 'docker-compose') {
                            unstash 'compose'
                            
                            if (env.CONTAINERS) {
                                sshagent(credentials: [env.SSH_CREDS_ID]) {
                                    sh "ssh -o StrictHostKeyChecking=no ${env.SERVER_USER}@${env.DEPLOY_TARGET_HOST} 'docker rm -f ${env.CONTAINERS} 2>/dev/null || true'"
                                }
                            }
                            
                            deployDockerCompose(
                                credentialsId: env.SSH_CREDS_ID,
                                user: env.SERVER_USER,
                                host: env.DEPLOY_TARGET_HOST,
                                dir: env.DEPLOY_TARGET_DIR,
                                composeFile: 'docker-compose.yml',
                                envVars: [
                                    'DOCKER_IMAGE': env.DOCKER_IMAGE
                                ]
                            )
                        } else if (env.STACK_TYPE == 'capacitor') {
                            unstash 'compose'
                            deployDockerCompose(
                                credentialsId: env.SSH_CREDS_ID,
                                user: env.SERVER_USER,
                                host: env.DEPLOY_TARGET_HOST,
                                dir: env.DEPLOY_TARGET_DIR,
                                composeFile: 'compose.yml'
                            )
                            if (env.DEPLOY_TARGET_PORT) {
                                echo "✅ Доступно: http://${env.DEPLOY_TARGET_HOST}:${env.DEPLOY_TARGET_PORT}"
                            }
                        }
                    }
                }
            }

            stage('Health Check') {
                when { expression { return env.STACK_TYPE == 'docker-compose' } }
                steps {
                    script {
                        if (env.HOST_PORT) {
                            echo "Verifying application availability..."
                            try {
                                checkHttpEndpoint(url: "http://${env.DEPLOY_TARGET_HOST}:${env.HOST_PORT}/api/health", retries: 5, sleepTime: 5)
                            } catch (Exception e) {
                                echo "Warning: API might not be ready. Error: ${e.message}"
                            }
                        }
                        
                        def containersList = env.CONTAINERS.split(' ')
                        for (String containerName : containersList) {
                            if (containerName.trim()) {
                                echo "Checking logs for ${containerName}..."
                                remoteDockerLogs(
                                    containerName: containerName,
                                    host: env.DEPLOY_TARGET_HOST,
                                    user: env.SERVER_USER,
                                    credentialsId: env.SSH_CREDS_ID,
                                    lines: 30
                                )
                            }
                        }
                    }
                }
            }
        }

        post {
            always {
                script {
                    if (env.STACK_TYPE == 'docker-compose' && env.DOCKER_IMAGE && env.BUILD_TAG) {
                        cleanLocalDockerImages(imageName: env.DOCKER_IMAGE, tag: env.BUILD_TAG)
                    }
                    if (env.STACK_TYPE == 'capacitor') {
                        sh 'docker image prune -f || true'
                    }
                    sh "docker run --rm -v \$(pwd):/workspace alpine chown -R \$(id -u):\$(id -g) /workspace || true"
                }
            }
            success {
                echo "✅ ${env.SERVICE_NAME} успешно собран! Build: ${env.BUILD_TAG}"
            }
            failure {
                echo "❌ ${env.SERVICE_NAME}: сборка упала."
            }
        }
    }
}
