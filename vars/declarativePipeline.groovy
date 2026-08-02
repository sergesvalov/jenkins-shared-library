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
        
        stage('Validate Pipeline Config') {
            validatePipelineConfig(configPath: 'pipeline-config.yaml')
        }
    }

    def isValidateOnly = params.VALIDATE_ONLY ?: env.VALIDATE_ONLY == 'true'
    if (!isValidateOnly && currentBuild.rawBuild) {
        try {
            def paramsAction = currentBuild.rawBuild.getAction(hudson.model.ParametersAction)
            if (paramsAction) {
                def valParam = paramsAction.getParameter('VALIDATE_ONLY')
                if (valParam) isValidateOnly = valParam.value
            }
        } catch (Exception e) {}
    }

    if (isValidateOnly) {
        echo "VALIDATE_ONLY is true. Configuration is valid, skipping actual build pipeline."
        currentBuild.result = 'SUCCESS'
        return
    }

    if (config.stack_type == 'docker-compose') {
        dockerComposePipeline(agent: buildAgent, config: config, gitCommit: gitCommit, params: params)
    } else if (config.stack_type == 'capacitor' || config.stack_type == 'node') {
        capacitorPipeline(agent: buildAgent, config: config, gitCommit: gitCommit, params: params)
    } else {
        error("Unsupported stack_type: ${config.stack_type}")
    }
}
