def call(Map config) {
    sshagent(credentials: [config.credentialsId]) {
        sh "ssh -o StrictHostKeyChecking=no ${config.user}@${config.host} 'mkdir -p ${config.dir}'"
        sh "find . -maxdepth 1 -type f \\( -name '*.yml' -o -name '*.yaml' -o -name '*.conf' \\) -exec scp -o StrictHostKeyChecking=no {} ${config.user}@${config.host}:${config.dir}/ \\;"
        
        if (config.envVars) {
            def envString = config.envVars.collect { k, v -> "${k}=${v}" }.join('\n')
            writeFile file: '.env', text: envString
            sh "scp -o StrictHostKeyChecking=no .env ${config.user}@${config.host}:${config.dir}/.env"
        }
        
        sh "ssh -o StrictHostKeyChecking=no ${config.user}@${config.host} 'cd ${config.dir} && docker compose pull && docker compose up -d --remove-orphans'"
    }
}
