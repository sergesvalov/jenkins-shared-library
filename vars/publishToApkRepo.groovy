/**
 * Publishes a signed APK to the APK Repo service (POST /api/v1/apps/<appId>/upload), the store
 * the Android apps update themselves from. Replaces the curl block copied between projects.
 *
 *   publishToApkRepo(
 *       apk:         'app/build/outputs/apk/release/myapp-v1.0.42.apk',
 *       appId:       'myapp',
 *       title:       'My App',
 *       packageName: 'com.example.myapp',
 *       versionName: "1.0.${env.BUILD_NUMBER}"
 *   )
 *
 * Options:
 *   apk, appId, title, packageName, versionName   required
 *   versionCode         default env.BUILD_NUMBER
 *   repoUrl             default env.APK_REPO_URL (e.g. http://<host>:8060)
 *   tokenCredentialsId  Jenkins secret-text credential with the publish token, default 'apk-publish-token'
 *   changelog           default "Jenkins build #N (<short sha>)"
 *   failBuild           false (default): a failed upload marks the stage UNSTABLE — the APK is still in the
 *                       build artifacts; true: the build fails.
 * Returns true when the repo accepted the APK.
 */
def call(Map args) {
    def apk = args.apk ?: error('publishToApkRepo: apk is required')
    def appId = args.appId ?: error('publishToApkRepo: appId is required')
    def title = args.title ?: error('publishToApkRepo: title is required')
    def packageName = args.packageName ?: error('publishToApkRepo: packageName is required')
    def versionName = args.versionName ?: error('publishToApkRepo: versionName is required')
    def versionCode = (args.versionCode ?: env.BUILD_NUMBER).toString()
    def repoUrl = (args.repoUrl ?: env.APK_REPO_URL ?: error('publishToApkRepo: repoUrl (or env.APK_REPO_URL) is required')).replaceAll('/+$', '')
    def tokenId = args.tokenCredentialsId ?: 'apk-publish-token'
    def failBuild = args.failBuild ?: false

    if (!fileExists(apk)) {
        def msg = "publishToApkRepo: ${apk} not found"
        if (failBuild) { error(msg) }
        unstable(msg)
        return false
    }
    def changelog = args.changelog ?: "Jenkins build #${env.BUILD_NUMBER} (${sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()})"

    def ok = false
    withCredentials([string(credentialsId: tokenId, variable: 'APK_TOKEN')]) {
        // Values go through the environment, not into the script text: no quoting surprises, token never echoed.
        withEnv([
            "APKR_URL=${repoUrl}/api/v1/apps/${appId}/upload", "APKR_FILE=${apk}", "APKR_APP=${appId}",
            "APKR_TITLE=${title}", "APKR_PKG=${packageName}", "APKR_CODE=${versionCode}",
            "APKR_NAME=${versionName}", "APKR_BUILD=${env.BUILD_NUMBER}", "APKR_LOG=${changelog}",
        ]) {
            def rc = sh(returnStatus: true, script: '''
                set +x
                RESP=$(mktemp)
                CODE=$(curl -sS -m 300 -o "$RESP" -w "%{http_code}" -X POST "$APKR_URL" \
                    -H "Expect:" -H "X-Publish-Token: $APK_TOKEN" \
                    -F "file=@$APKR_FILE" -F "app_id=$APKR_APP" -F "title=$APKR_TITLE" \
                    -F "package_name=$APKR_PKG" -F "version_code=$APKR_CODE" -F "version_name=$APKR_NAME" \
                    -F "build_number=$APKR_BUILD" -F "source=jenkins" -F "changelog=$APKR_LOG") || CODE=000
                cat "$RESP" 2>/dev/null; echo
                rm -f "$RESP"
                if [ "$CODE" -lt 200 ] || [ "$CODE" -ge 300 ]; then
                    echo "APK Repo did not accept $APKR_APP $APKR_NAME: HTTP $CODE"
                    exit 1
                fi
                echo "Published $APKR_APP $APKR_NAME (code $APKR_CODE)"
            ''')
            ok = (rc == 0)
        }
    }
    if (!ok) {
        def msg = "publishToApkRepo: ${appId} ${versionName} was not published"
        if (failBuild) { error(msg) }
        unstable(msg)
    }
    return ok
}
