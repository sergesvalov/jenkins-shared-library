def call(String image, String tag, String dockerfile, String label) {
    def exists = !params.FORCE_REBUILD_IMAGES &&
                 sh(script: "docker pull ${image}:${tag} > /dev/null 2>&1", returnStatus: true) == 0
    if (exists) {
        echo "✅ ${label}-образ ${tag} уже есть в реестре — пересборка не нужна"
    } else {
        echo "🐳 Сборка ${label}-образа (${tag})..."
        sh "docker build -t ${image}:${tag} -t ${image}:latest -f ${dockerfile} ."
        sh "docker push ${image}:${tag}"
        sh "docker push ${image}:latest"
    }
}
