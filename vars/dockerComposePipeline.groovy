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
                        stash name: 'compose', includes: 'docker-compose.yml'
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
                        
                        def deployEnvVars = [
                            'DOCKER_IMAGE': env.DOCKER_IMAGE,
                            'BUILD_TAG': env.BUILD_TAG,
                            'REGISTRY_IP': env.REGISTRY_IP
                        ]
                        
                        if (config.envVars) {
                            config.envVars.each { varName ->
                                deployEnvVars[varName] = env[varName] ?: ''
                            }
                        }
                        
                        deployDockerCompose(
                            credentialsId: env.SSH_CREDS_ID,
                            user: env.SERVER_USER,
                            host: env.DEPLOY_TARGET_HOST,
                            dir: env.DEPLOY_TARGET_DIR,
                            composeFile: 'docker-compose.yml',
                            envVars: deployEnvVars
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
