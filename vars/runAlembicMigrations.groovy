def call(Map config = [:]) {
    def composeFile = config.get('composeFile', 'docker-compose.yml')
    def service = config.get('service', 'backend')
    def delay = config.get('delay', 20)
    
    sleep time: delay, unit: 'SECONDS'
    sh "docker compose -f ${composeFile} logs ${service}"
    sh "docker compose -f ${composeFile} exec -T ${service} alembic upgrade head"
}
