// Builds and pushes a Docker image only when its Dockerfile (or tag) has changed.
//
// Calculates a content hash of the Dockerfile (if tag is omitted) and checks whether
// image:tag is already present in the registry. If present, building is skipped,
// saving build time on CI workers.
//
// Usage:
//   // Named arguments with automatic SHA1 tagging:
//   buildAndPushIfChanged(
//       image:      env.BUILDER_IMAGE,
//       dockerfile: 'Dockerfile.android',
//       label:      'Android Builder'
//   )
//
//   // Positional arguments (legacy compatibility):
//   buildAndPushIfChanged(env.BUILDER_IMAGE, '12charhash', 'Dockerfile.android', 'Android Builder')
def call(Map args) {
    def image = args.image ?: error("buildAndPushIfChanged: 'image' parameter is required")
    def dockerfile = args.dockerfile ?: 'Dockerfile'
    def label = args.label ?: image.substring(image.lastIndexOf('/') + 1)
    def context = args.context ?: '.'
    def extraArgs = args.extraArgs ? "${args.extraArgs} " : ''
    def forceRebuild = args.containsKey('forceRebuild') ? args.forceRebuild : (params.FORCE_REBUILD_IMAGES ?: false)

    def tag = args.tag
    if (!tag) {
        tag = sh(script: "sha1sum '${dockerfile}' | cut -c1-12", returnStdout: true).trim()
    }

    def exists = !forceRebuild &&
                 sh(script: "docker pull ${image}:${tag} > /dev/null 2>&1", returnStatus: true) == 0

    if (exists) {
        echo "✅ ${label} image (${tag}) already exists in registry — rebuild not needed"
    } else {
        echo "🐳 Building ${label} image (${tag})..."
        sh "docker build -t ${image}:${tag} -t ${image}:latest -f '${dockerfile}' ${extraArgs}${context}"
        sh "docker push ${image}:${tag}"
        sh "docker push ${image}:latest"
    }
    return tag
}

def call(String image, String tag, String dockerfile, String label) {
    call(image: image, tag: tag, dockerfile: dockerfile, label: label)
}