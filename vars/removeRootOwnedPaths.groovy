// Removes build output that containers left behind as root (Gradle's build/ and .gradle/, ...):
// the jenkins user can't delete it, and the next checkout or build would fail on it. Runs a small
// container to remove it. Missing paths are fine; it never fails the build.
//
// Usage (in `post { always { ... } }`):
//   removeRootOwnedPaths(dir: 'android', paths: ['build', 'app/build', '.gradle', '.kotlin'])
//
// Options:
//   dir     directory in the workspace the paths are relative to (required)
//   paths   what to remove inside it (required; no '..')
//   image   the container image (default 'alpine:3.20')
def call(Map args = [:]) {
    def dir = args.dir ?: error('removeRootOwnedPaths: dir is required')
    def paths = args.paths ?: error('removeRootOwnedPaths: paths are required')
    if (paths.any { it.contains('..') } || dir.contains('..')) {
        error('removeRootOwnedPaths: paths must stay inside the directory')
    }
    def targets = paths.collect { "'/a/${it}'" }.join(' ')
    sh """
        if [ -d '${dir}' ]; then
            docker run --rm -v "\$(pwd)/${dir}":/a ${args.image ?: 'alpine:3.20'} sh -c "rm -rf ${targets}" || true
        fi
    """
}
