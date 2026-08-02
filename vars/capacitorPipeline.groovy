def call(Map args) {
    def buildAgent = args.agent
    def config = args.config
    def gitCommit = args.gitCommit
    def userParams = args.params

    pipeline {
        agent { label "${buildAgent}" }
        options {
            skipDefaultCheckout()
        }
        parameters {
            booleanParam(name: 'VALIDATE_ONLY',        defaultValue: false, description: 'Only validate config and show generated Jenkinsfile')
            booleanParam(name: 'SKIP_TYPECHECK',       defaultValue: false, description: 'Skip TypeScript check (if applicable)')
            booleanParam(name: 'BUILD_WEB',             defaultValue: true,  description: 'Build web version and deploy')
            booleanParam(name: 'BUILD_ANDROID',         defaultValue: true,  description: 'Build Android .apk (if applicable)')
            booleanParam(name: 'FORCE_DEPLOY',          defaultValue: false, description: 'Deploy web even if not main branch')
            booleanParam(name: 'FORCE_REBUILD_IMAGES',  defaultValue: false, description: 'Rebuild toolchain images even if Dockerfile unchanged')
        }
        stages {
            stage('Source Checkout & Setup') {
                steps {
                    script {
                        // Clean up root files that might have been left by previous runs
                        sh 'docker run --rm -v $(pwd):/workspace alpine chown -R $(id -u):$(id -g) /workspace || true'
                        
                        checkout scm
                        
                        // Deep cleanup for workspace
                        sh 'docker run --rm -u root -v "$WORKSPACE:$WORKSPACE" -w "$WORKSPACE" node:22-bookworm-slim rm -rf dist release android playwright-report test-results || true'
                        
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
                        
                        env.NODE_IMAGE = "${env.REGISTRY_IP}:5050/${config.service_name}-builder"
                        env.ANDROID_IMAGE = "${env.REGISTRY_IP}:5050/${config.service_name}-android"
                        env.WEB_IMAGE = "${env.REGISTRY_IP}:5050/${config.service_name}-web"
                        
                        env.NPM_CACHE_VOLUME = "${config.service_name}-npm-cache"
                        env.GRADLE_CACHE_VOLUME = "${config.service_name}-gradle-cache"
                        env.BUILD_TAG = "${env.BUILD_NUMBER}-${gitCommit.take(7)}"
                        
                        if (config.deploy) {
                            env.DEPLOY_TARGET_HOST = config.deploy.host ?: env.DEPLOY_SERVER_IP
                            env.DEPLOY_TARGET_DIR = config.deploy.dir ?: "~/${config.service_name}"
                            env.DEPLOY_TARGET_PORT = config.deploy.web_port ? config.deploy.web_port.toString() : ""
                        }
                    }
                }
            }

            stage('Build Toolchain Images') {
                steps {
                    script {
                        env.NODE_IMAGE_TAG = sh(script: 'sha1sum Dockerfile.build | cut -c1-12', returnStdout: true).trim()
                        if (userParams.FORCE_REBUILD_IMAGES) {
                            buildAndPushDockerImage(imageName: env.NODE_IMAGE, tag: env.NODE_IMAGE_TAG, context: '.', extraArgs: '-f Dockerfile.build')
                        } else {
                            buildAndPushIfChanged(env.NODE_IMAGE, env.NODE_IMAGE_TAG, 'Dockerfile.build', 'Node')
                        }

                        if (userParams.BUILD_ANDROID) {
                            env.ANDROID_IMAGE_TAG = sh(script: 'sha1sum Dockerfile.android | cut -c1-12', returnStdout: true).trim()
                            if (userParams.FORCE_REBUILD_IMAGES) {
                                buildAndPushDockerImage(imageName: env.ANDROID_IMAGE, tag: env.ANDROID_IMAGE_TAG, context: '.', extraArgs: '-f Dockerfile.android')
                            } else {
                                buildAndPushIfChanged(env.ANDROID_IMAGE, env.ANDROID_IMAGE_TAG, 'Dockerfile.android', 'Android')
                            }
                        }
                    }
                }
            }

            stage('Install Dependencies') {
                steps {
                    script {
                        echo "📦 npm ci in workspace..."
                        withNodeBuilder {
                            sh 'npm install --ignore-scripts'
                        }
                    }
                }
            }

            stage('TypeScript Check') {
                when { 
                    expression { return env.HAS_FEATURE_TYPECHECK == 'true' && !userParams.SKIP_TYPECHECK } 
                }
                steps {
                    script {
                        echo "🔍 Running tsc --noEmit..."
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
                            echo "🧪 Running Unit tests (Vitest)..."
                            withNodeBuilder {
                                sh 'npm run test'
                            }
                        }

                        if (env.HAS_FEATURE_E2E == 'true') {
                            echo "🎭 Running E2E tests (Playwright)..."
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
                    expression { return userParams.BUILD_WEB } 
                }
                steps {
                    script {
                        echo "🌐 Vite web build..."
                        withNodeBuilder {
                            sh 'VITE_MODE=web npm run build:web'
                        }
                        
                        echo "🐳 Building Nginx image with dist/ inside..."
                        sh "docker build -t ${env.WEB_IMAGE}:${env.BUILD_TAG} -t ${env.WEB_IMAGE}:latest -f Dockerfile.nginx ."
                        sh "docker push ${env.WEB_IMAGE}:${env.BUILD_TAG}"
                        sh "docker push ${env.WEB_IMAGE}:latest"

                        stash name: 'compose', includes: 'compose.yml'
                    }
                }
            }

            stage('Build: Android (.apk)') {
                when { 
                    expression { return userParams.BUILD_ANDROID } 
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

            stage('Deploy') {
                when {
                    anyOf {
                        branch 'main'
                        expression { return userParams.FORCE_DEPLOY }
                    }
                    expression { return userParams.BUILD_WEB }
                }
                steps {
                    script {
                        echo "🚀 Docker deploy to ${env.DEPLOY_TARGET_HOST} ..."
                        unstash 'compose'
                        deployDockerCompose(
                            credentialsId: env.SSH_CREDS_ID,
                            user: env.SERVER_USER,
                            host: env.DEPLOY_TARGET_HOST,
                            dir: env.DEPLOY_TARGET_DIR,
                            composeFile: 'compose.yml'
                        )
                        if (env.DEPLOY_TARGET_PORT) {
                            echo "✅ Available at: http://${env.DEPLOY_TARGET_HOST}:${env.DEPLOY_TARGET_PORT}"
                        }
                    }
                }
            }
        }
        post {
            always {
                script {
                    sh 'docker image prune -f || true'
                    sh "docker run --rm -v \$(pwd):/workspace alpine chown -R \$(id -u):\$(id -g) /workspace || true"
                }
            }
            success {
                echo "✅ ${env.SERVICE_NAME} built successfully! Build: ${env.BUILD_TAG}"
            }
            failure {
                echo "❌ ${env.SERVICE_NAME}: build failed."
            }
        }
    }
}
