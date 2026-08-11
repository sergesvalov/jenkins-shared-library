def call(Map config) {
    sshagent(credentials: [config.credentialsId]) {
        sh "ssh -o StrictHostKeyChecking=no ${config.user}@${config.host} 'mkdir -p ${config.dir}'"
        sh "find . -maxdepth 1 -type f \\( -name '*.yml' -o -name '*.yaml' -o -name '*.conf' \\) -exec scp -o StrictHostKeyChecking=no {} ${config.user}@${config.host}:${config.dir}/ \\;"
        
        if (config.envVars) {
            def envString = config.envVars.collect { k, v -> "${k}=${v}" }.join('\n')
            writeFile file: '.env.deploy', text: envString
            sh "scp -o StrictHostKeyChecking=no .env.deploy ${config.user}@${config.host}:${config.dir}/.env.deploy"
        }
        
        sh "ssh -o StrictHostKeyChecking=no ${config.user}@${config.host} 'cd ${config.dir} && touch .env && docker compose --env-file .env --env-file .env.deploy pull && docker compose --env-file .env --env-file .env.deploy up -d --remove-orphans'"
    }
}
