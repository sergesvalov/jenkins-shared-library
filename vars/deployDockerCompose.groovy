def call(Map config) {
    sshagent(credentials: [config.credentialsId]) {
        sh "ssh -o StrictHostKeyChecking=no ${config.user}@${config.host} 'mkdir -p ${config.dir}'"
        sh "scp -o StrictHostKeyChecking=no ${config.composeFile} ${config.user}@${config.host}:${config.dir}/${config.composeFile}"
        sh "ssh -o StrictHostKeyChecking=no ${config.user}@${config.host} 'cd ${config.dir} && docker compose pull && docker compose up -d --remove-orphans'"
    }
}
