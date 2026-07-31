def call(Map params) {
    def imageName = params.imageName
    def tag = params.tag ?: env.BUILD_NUMBER
    def context = params.context ?: '.'
    def extraArgs = params.extraArgs ?: ''
    
    echo "Building Docker image: ${imageName}:${tag}"
    sh "docker build -t ${imageName}:${tag} -t ${imageName}:latest ${extraArgs} ${context}"
    
    echo "Pushing Docker images..."
    sh "docker push ${imageName}:${tag}"
    sh "docker push ${imageName}:latest"
}
