def call(Map args) {
    def buildAgent = args.agent
    def config = args.config
    def gitCommit = args.gitCommit
    def userParams = args.params

    pipeline {
        agent { label "${buildAgent}" }
        // Стандарт для всех сборок (README → «Эталонный Jenkinsfile»): сборка не висит вечно,
        // не идёт параллельно сама с собой и не копит историю.
        options {
            skipDefaultCheckout()
            timeout(time: 60, unit: 'MINUTES')
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '25', artifactNumToKeepStr: '5'))
        }
        parameters {
            booleanParam(name: 'VALIDATE_ONLY', defaultValue: false, description: 'Only validate config and show generated Jenkinsfile')
            booleanParam(name: 'FORCE_DEPLOY', defaultValue: false, description: 'Deploy even if not main branch')
        }
        stages {
            stage('Source Checkout & Setup') {
                steps {
                    script {
                        checkout scm
                        
                        env.SERVICE_NAME = config.service_name
                        env.STACK_TYPE = config.stack_type
                        env.DEPLOY_SERVER_IP = config.target_cluster == 'prod' ? env.PROD_SERVER_IP : (env.STAGING_SERVER_IP ?: '127.0.0.1')
                        env.REGISTRY_IP = env.REGISTRY_IP ?: '127.0.0.1'
                        env.SSH_CREDS_ID = env.SERVER_USER
                        env.SERVER_USER = env.SERVER_USER
                        env.DOCKER_IMAGE = "${env.REGISTRY_IP}:5050/${config.service_name}"
                        env.BUILD_TAG = "${env.BUILD_NUMBER}-${gitCommit.take(7)}"
                        env.HOST_PORT = config.ports?.host ? config.ports.host.toString() : ""
                        
                        if (config.deploy) {
                            env.DEPLOY_TARGET_HOST = config.deploy.host ?: env.DEPLOY_SERVER_IP
                            env.DEPLOY_TARGET_DIR = config.deploy.dir ?: "~/${config.service_name}"
                        } else {
                            env.DEPLOY_TARGET_HOST = env.DEPLOY_SERVER_IP
                            env.DEPLOY_TARGET_DIR = "~/${env.SERVICE_NAME}"
                        }
                        
                        env.CONTAINERS = (config.containers ?: []).join(' ')
                    }
                }
            }

            stage('Build & Push Image') {
                steps {
                    script {
                        if (config.images) {
                            config.images.each { img ->
                                def imgName = "${env.REGISTRY_IP}:5050/${img.name}"
                                buildAndPushDockerImage(imageName: imgName, tag: env.BUILD_TAG, context: img.context ?: '.')
                            }
                        } else {
                            buildAndPushDockerImage(imageName: env.DOCKER_IMAGE, tag: env.BUILD_TAG)
                        }
                        stash name: 'compose', includes: 'docker-compose*.yml, *.yaml, *.yml, *.conf'
                    }
                }
            }

            stage('Deploy') {
                when {
                    anyOf {
                        branch 'main'
                        expression { return userParams.FORCE_DEPLOY }
                    }
                }
                steps {
                    script {
                        echo "🚀 Docker deploy to ${env.DEPLOY_TARGET_HOST} ..."
                        unstash 'compose'
                        
                        if (env.CONTAINERS) {
                            sshagent(credentials: [env.SSH_CREDS_ID]) {
                                sh "ssh -o StrictHostKeyChecking=no ${env.SERVER_USER}@${env.DEPLOY_TARGET_HOST} 'docker rm -f ${env.CONTAINERS} 2>/dev/null || true'"
                            }
                        }
                        
                        def deployVars = [
                            'DOCKER_IMAGE': env.DOCKER_IMAGE,
                            'BUILD_TAG': env.BUILD_TAG,
                            'REGISTRY_IP': env.REGISTRY_IP
                        ]
                        
                        if (config.envVars) {
                            config.envVars.each { varName ->
                                if (env[varName] != null) {
                                    deployVars[varName] = env[varName]
                                }
                            }
                        }
                        
                        deployDockerCompose(
                            credentialsId: env.SSH_CREDS_ID,
                            user: env.SERVER_USER,
                            host: env.DEPLOY_TARGET_HOST,
                            dir: env.DEPLOY_TARGET_DIR,
                            composeFile: 'docker-compose.yml',
                            envVars: deployVars
                        )
                        
                        if (config.migrations) {
                            def delay = (config.migrations instanceof Map && config.migrations.delay) ? config.migrations.delay : 20
                            def service = (config.migrations instanceof Map && config.migrations.service) ? config.migrations.service : 'backend'
                            def composeFile = (config.migrations instanceof Map && config.migrations.composeFile) ? config.migrations.composeFile : 'docker-compose.yml'
                            
                            echo "Running Database Migrations..."
                            echo "Waiting ${delay}s for DB to be ready..."
                            sleep time: delay, unit: 'SECONDS'
                            
                            sshagent(credentials: [env.SSH_CREDS_ID]) {
                                sh "ssh -o StrictHostKeyChecking=no ${env.SERVER_USER}@${env.DEPLOY_TARGET_HOST} 'cd ${env.DEPLOY_TARGET_DIR} && docker compose -f ${composeFile} logs ${service}'"
                                sh "ssh -o StrictHostKeyChecking=no ${env.SERVER_USER}@${env.DEPLOY_TARGET_HOST} 'cd ${env.DEPLOY_TARGET_DIR} && docker compose -f ${composeFile} exec -T ${service} alembic upgrade head'"
                            }
                        }
                    }
                }
            }

            stage('Health Check') {
                when {
                    anyOf {
                        branch 'main'
                        expression { return userParams.FORCE_DEPLOY }
                    }
                }
                steps {
                    script {
                        // Контейнеры (запущен, не в цикле перезапусков, не unhealthy) и health-эндпоинт;
                        // проблема — сборка UNSTABLE и хвост логов упавших контейнеров в выводе.
                        def hcUrl = config.healthcheck_url ?: (env.HOST_PORT ? "http://${env.DEPLOY_TARGET_HOST}:${env.HOST_PORT}/api/health" : null)
                        verifyDeployment(
                            host: env.DEPLOY_TARGET_HOST,
                            user: env.SERVER_USER,
                            credentialsId: env.SSH_CREDS_ID,
                            containers: (config.containers ?: []) as List,
                            healthUrl: hcUrl,
                            healthCodes: (config.healthcheck_codes ?: [200]) as List
                        )
                    }
                }
            }
        }
        post {
            always {
                script {
                    if (config.images) {
                        config.images.each { img ->
                            cleanLocalDockerImages(imageName: "${env.REGISTRY_IP}:5050/${img.name}", tag: env.BUILD_TAG)
                        }
                    } else if (env.DOCKER_IMAGE && env.BUILD_TAG) {
                        cleanLocalDockerImages(imageName: env.DOCKER_IMAGE, tag: env.BUILD_TAG)
                    }
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
