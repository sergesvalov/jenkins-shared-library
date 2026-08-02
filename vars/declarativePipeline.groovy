def call() {
    pipeline {
        agent any

        stages {
            stage('Source Checkout & Config') {
                steps {
                    script {
                        echo "Checking out source code..."
                        checkout scm
                        
                        echo "Reading pipeline-config.yaml..."
                        def config = readYaml file: 'pipeline-config.yaml'
                        
                        // Extracting variables into environment for easy access in other stages
                        env.SERVICE_NAME = config.service_name
                        env.STACK_TYPE = config.stack_type
                        
                        // Default to PROD_SERVER_IP if target_cluster is 'prod', else try to use STAGING
                        env.DEPLOY_SERVER_IP = config.target_cluster == 'prod' ? env.PROD_SERVER_IP : (env.STAGING_SERVER_IP ?: '127.0.0.1')
                        
                        env.REGISTRY_IP = env.REGISTRY_IP ?: '127.0.0.1'
                        env.SSH_CREDS_ID = env.SERVER_USER
                        env.SERVER_USER = env.SERVER_USER
                        
                        env.DOCKER_IMAGE = "${env.REGISTRY_IP}:5050/${config.service_name}"
                        env.BUILD_TAG = "${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
                        
                        env.HOST_PORT = config.ports?.host ? config.ports.host.toString() : ""
                        env.CONTAINERS = (config.containers ?: []).join(' ')
                    }
                }
            }

            stage('Build & Push') {
                steps {
                    script {
                        buildAndPushDockerImage(imageName: env.DOCKER_IMAGE, tag: env.BUILD_TAG)
                        // Stash docker-compose file for deployment stage
                        if (env.STACK_TYPE == 'docker-compose') {
                            stash name: 'compose', includes: 'docker-compose.yml'
                        }
                    }
                }
            }

            stage('Deploy') {
                steps {
                    script {
                        echo "Deploying to ${env.DEPLOY_SERVER_IP}..."
                        
                        if (env.STACK_TYPE == 'docker-compose') {
                            unstash 'compose'
                            
                            // Remove old standalone containers if they exist to prevent conflicts
                            if (env.CONTAINERS) {
                                sshagent(credentials: [env.SSH_CREDS_ID]) {
                                    sh "ssh -o StrictHostKeyChecking=no ${env.SERVER_USER}@${env.DEPLOY_SERVER_IP} 'docker rm -f ${env.CONTAINERS} 2>/dev/null || true'"
                                }
                            }
                            
                            deployDockerCompose(
                                credentialsId: env.SSH_CREDS_ID,
                                user: env.SERVER_USER,
                                host: env.DEPLOY_SERVER_IP,
                                dir: "~/${env.SERVICE_NAME}",
                                composeFile: 'docker-compose.yml',
                                envVars: [
                                    'DOCKER_IMAGE': env.DOCKER_IMAGE
                                ]
                            )
                        } else {
                            echo "Skipping deploy: Unsupported stack type ${env.STACK_TYPE}"
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
                                checkHttpEndpoint(url: "http://${env.DEPLOY_SERVER_IP}:${env.HOST_PORT}/api/health", retries: 5, sleepTime: 5)
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
                                    host: env.DEPLOY_SERVER_IP,
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
                    if (env.DOCKER_IMAGE && env.BUILD_TAG) {
                        cleanLocalDockerImages(imageName: env.DOCKER_IMAGE, tag: env.BUILD_TAG)
                    }
                    fixWorkspacePermissions()
                }
            }
            success {
                echo "${env.SERVICE_NAME} successfully deployed! Build: ${env.BUILD_TAG}"
            }
            failure {
                echo "${env.SERVICE_NAME} deployment failed."
            }
        }
    }
}
