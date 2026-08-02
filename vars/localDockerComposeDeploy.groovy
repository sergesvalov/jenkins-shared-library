def call(Map config = [:]) {
    def composeFile = config.get('composeFile', 'docker-compose.yml')
    sh "docker compose -f ${composeFile} build"
    sh "docker compose -f ${composeFile} up -d"
}
