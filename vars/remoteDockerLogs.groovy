def call(Map params) {
    def containerName = params.containerName
    def host = params.host
    def user = params.user
    def credentialsId = params.credentialsId
    def lines = params.lines ?: 30
    
    echo "Fetching logs for ${containerName} from ${host}..."
    sshagent(credentials: [credentialsId]) {
        sh "ssh -o StrictHostKeyChecking=no ${user}@${host} 'docker logs --tail ${lines} ${containerName}'"
    }
}
