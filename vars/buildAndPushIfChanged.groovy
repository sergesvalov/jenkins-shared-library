def call(String image, String tag, String dockerfile, String label) {
    def exists = !params.FORCE_REBUILD_IMAGES &&
                 sh(script: "docker pull ${image}:${tag} > /dev/null 2>&1", returnStatus: true) == 0
    if (exists) {
        echo "✅ ${label} image ${tag} already exists in registry — rebuild not needed"
    } else {
        echo "🐳 Building ${label} image (${tag})..."
        sh "docker build -t ${image}:${tag} -t ${image}:latest -f ${dockerfile} ."
        sh "docker push ${image}:${tag}"
        sh "docker push ${image}:latest"
    }
}
