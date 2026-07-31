def call(Map params) {
    def imageName = params.imageName
    def tag = params.tag
    
    echo "Cleaning local Docker images for ${imageName}..."
    if (tag) {
        sh "docker rmi ${imageName}:${tag} || true"
    }
    sh "docker rmi ${imageName}:latest || true"
    
    // Clean up dangling images occasionally
    sh "docker image prune -f || true"
}
