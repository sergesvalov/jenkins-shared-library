// Whether anything under `path` changed since the last successful build of this job (true on the
// first build, or when that commit is unknown). For skipping slow stages, e.g. an Android APK
// when only the web app changed.
//
// Usage:
//   when { expression { return params.BUILD_ANDROID || changedSinceLastSuccess('android') } }
def call(String path) {
    def previous = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT
    if (!previous) {
        return true
    }
    return sh(script: "git diff --quiet ${previous} HEAD -- '${path}'", returnStatus: true) != 0
}
