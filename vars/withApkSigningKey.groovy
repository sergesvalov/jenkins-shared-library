// Runs `body` with the Android release signing key available to it, then deletes the key again.
//
// The key comes from Jenkins credentials, which Jenkins fills from OpenBao (JCasC). This library
// is public, so it holds no secrets at all - only the credential IDs, which every project may
// override. Inside `body` these environment variables are set:
//
//   APK_KEYSTORE_FILE      path of the keystore, restored from base64 into a private temp dir
//   APK_KEYSTORE_PASSWORD  keystore password (also used as the key password)
//   APK_KEY_ALIAS          key alias inside the keystore
//   APK_SIGNING            "true" when the key is there, "false" when signing was skipped
//
// Pass the secrets on to Gradle/apksigner by variable name, never on a command line
// (e.g. `docker run -e APK_KEYSTORE_PASSWORD ...`, `apksigner --ks-pass env:APK_KEYSTORE_PASSWORD`),
// so they don't show up in process lists or build logs.
//
// Usage:
//   withApkSigningKey {
//       sh 'docker run ... -v "$APK_KEYSTORE_FILE":/signing/release.keystore:ro -e APK_KEYSTORE_PASSWORD -e APK_KEY_ALIAS ...'
//   }
//
// Options (all optional):
//   keystoreCredentialsId  secret text with the base64 keystore   (default 'apk-keystore-base64')
//   passwordCredentialsId  secret text with the keystore password (default 'apk-sign-key')
//   aliasCredentialsId     secret text with the key alias          (default 'apk-key-alias')
//   required               true (default): fail when the credentials are missing;
//                          false: run body unsigned (APK_SIGNING=false) and only warn
//   keyDir                 where the keystore is restored (default: a temp dir in the workspace;
//                          it must be visible to the build, e.g. mounted into its container)
def call(Map args = [:], Closure body) {
    def keystoreCredentialsId = args.keystoreCredentialsId ?: 'apk-keystore-base64'
    def passwordCredentialsId = args.passwordCredentialsId ?: 'apk-sign-key'
    def aliasCredentialsId    = args.aliasCredentialsId ?: 'apk-key-alias'
    def required              = args.containsKey('required') ? args.required : true
    def keyDir                = args.keyDir ?: "${env.WORKSPACE}/.apk-signing-${env.BUILD_NUMBER ?: 'local'}"

    def credentials = [
        string(credentialsId: keystoreCredentialsId, variable: 'APK_KEYSTORE_BASE64'),
        string(credentialsId: passwordCredentialsId, variable: 'APK_KEYSTORE_PASSWORD'),
        string(credentialsId: aliasCredentialsId, variable: 'APK_KEY_ALIAS'),
    ]

    def signed = { ->
        withEnv(["APK_KEY_DIR=${keyDir}", "APK_KEYSTORE_FILE=${keyDir}/release.keystore", 'APK_SIGNING=true']) {
            try {
                // umask: the restored keystore is readable by this user only.
                sh '''
                    set -e
                    umask 077
                    mkdir -p "$APK_KEY_DIR"
                    printf '%s' "$APK_KEYSTORE_BASE64" | base64 -d > "$APK_KEYSTORE_FILE"
                    [ -s "$APK_KEYSTORE_FILE" ] || { echo "withApkSigningKey: the keystore credential is empty"; exit 1; }
                '''
                body()
            } finally {
                sh 'rm -rf "$APK_KEY_DIR"'
            }
        }
    }

    try {
        withCredentials(credentials) { signed() }
    } catch (Exception e) {
        if (required) {
            error("withApkSigningKey: signing credentials not found (${e.message}). " +
                  "Check that OpenBao is unsealed and Jenkins credentials '${keystoreCredentialsId}', " +
                  "'${passwordCredentialsId}', '${aliasCredentialsId}' exist.")
        }
        echo "[WARN] withApkSigningKey: signing credentials not found (${e.message}) - building without the release key."
        withEnv(['APK_SIGNING=false', 'APK_KEYSTORE_FILE=', 'APK_KEYSTORE_PASSWORD=', 'APK_KEY_ALIAS=']) {
            body()
        }
    }
}
