def call(Map params = [:]) {
    if (!params.agent) {
        error("You must specify an agent parameter, e.g., declarativePipeline(agent: 'built-in')")
    }
    def buildAgent = params.agent
    def config = null
    def gitCommit = ''

    // Allocate temporary node for quick configuration read
    node(buildAgent) {
        checkout scm
        config = readYaml file: 'pipeline-config.yaml'
        gitCommit = sh(script: 'git rev-parse HEAD || echo "local"', returnStdout: true).trim()
    }

    if (config.stack_type == 'docker-compose') {
        dockerComposePipeline(agent: buildAgent, config: config, gitCommit: gitCommit, params: params)
    } else if (config.stack_type == 'capacitor' || config.stack_type == 'node') {
        capacitorPipeline(agent: buildAgent, config: config, gitCommit: gitCommit, params: params)
    } else {
        error("Unsupported stack_type: ${config.stack_type}")
    }
}
