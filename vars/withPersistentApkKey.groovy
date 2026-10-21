// Runs `body` with a persistent Android release signing key - the foodplanner way: the keystore
// is created once and kept in a Jenkins cache directory (never in git), so every build signs with
// the same key and a new APK installs over the old one. Its password comes from Jenkins
// credentials, which Jenkins fills from OpenBao (JCasC).
//
// This library is public: it holds no secrets - only credential IDs. A project's former password
// (legacyPassword) is passed by the project itself, never defaulted here.
//
// Inside `body` these environment variables are set:
//
//   APK_KEYSTORE_DIR       the cache directory with the keystore (mount it into the build container)
//   APK_KEYSTORE_FILE      the keystore in it: "$APK_KEYSTORE_DIR/release.keystore"
//   APK_KEYSTORE_PASSWORD  keystore password (also the key password)
//   APK_KEY_ALIAS          key alias inside the keystore
//
// Pass the password on by variable name, never on a command line (`docker run -e APK_KEYSTORE_PASSWORD`,
// `keytool -storepass:env APK_KEYSTORE_PASSWORD`, `apksigner --ks-pass env:APK_KEYSTORE_PASSWORD`).
//
// The key:
//   - missing: created (RSA, 9999 days) with the password from the credentials;
//   - opens with that password: used as it is;
//   - opens only with legacyPassword (a project moving its password to OpenBao): re-encrypted once
//     with the credentials' password - the same key (a copy stays as release.keystore.bak), so
//     installed apps still update;
//   - opens with neither: the step fails and leaves the key alone. A new key would break updates
//     (the app has to be reinstalled on every phone) - a person decides that.
//
// Usage:
//   withPersistentApkKey(keyDir: "${env.WORKSPACE}/../myapp-caches/signing", alias: 'myapp') {
//       sh 'docker run ... -v "$APK_KEYSTORE_DIR":/signing:ro -e APK_KEYSTORE_FILE=/signing/release.keystore -e APK_KEYSTORE_PASSWORD -e APK_KEY_ALIAS ...'
//   }
//
// Options:
//   keyDir                  the cache directory for the keystore (required)
//   alias                   the key alias (required)
//   passwordCredentialsId   secret text with the password, filled from OpenBao (default 'apk-sign-key')
//   fallbackCredentialsId   secret text used when the first one is missing or empty (optional)
//   legacyPassword          the password the key was created with before (optional, see above)
//   dname                   the certificate's subject for a new key (default "CN=<alias>")
//   builderImage            image with keytool (default 'mingc/android-build-box:latest')
def call(Map args = [:], Closure body) {
    def keyDir = args.keyDir ?: error('withPersistentApkKey: keyDir is required')
    def alias = args.alias ?: error('withPersistentApkKey: alias is required')
    def passwordCredentialsId = args.passwordCredentialsId ?: 'apk-sign-key'
    def fallbackCredentialsId = args.fallbackCredentialsId
    def credentialsId = pickPasswordCredentials(passwordCredentialsId, fallbackCredentialsId)

    def vars = [
        "APK_KEYSTORE_DIR=${keyDir}",
        "APK_KEYSTORE_FILE=${keyDir}/release.keystore",
        "APK_KEY_ALIAS=${alias}",
        "APK_KEY_DNAME=${args.dname ?: "CN=${alias}"}",
        "APK_KEYTOOL_IMAGE=${args.builderImage ?: 'mingc/android-build-box:latest'}",
        "APK_PASSWORD_CREDENTIALS=${credentialsId}",
    ]
    withEnv(vars) {
        withCredentials([string(credentialsId: credentialsId, variable: 'APK_KEYSTORE_PASSWORD')]) {
            withEnv(["APK_LEGACY_PASSWORD=${args.legacyPassword ?: ''}"]) {
                ensureKey()
            }
            body()
        }
    }
}

// The password from OpenBao when it is there and not empty; else the fallback (with a warning).
// No stand-in like '123': the key is persistent and opens with its own password only.
String pickPasswordCredentials(String primary, String fallback) {
    def found = false
    try {
        withCredentials([string(credentialsId: primary, variable: 'APK_PASSWORD_PROBE')]) {
            found = (env.APK_PASSWORD_PROBE ?: '').trim() != ''
        }
    } catch (Exception e) {
        echo "[WARN] withPersistentApkKey: credentials '${primary}' are not available: ${e.message}"
    }
    if (found) {
        echo "withPersistentApkKey: the key password comes from OpenBao (credentials '${primary}')"
        return primary
    }
    if (!fallback) {
        error("withPersistentApkKey: no key password in credentials '${primary}'. " +
              "Check that OpenBao is unsealed and the credentials exist.")
    }
    echo "[WARN] withPersistentApkKey: no key password in '${primary}' - using credentials '${fallback}'"
    return fallback
}

void ensureKey() {
    sh '''
        set +x
        mkdir -p "$APK_KEYSTORE_DIR"
        keytool() {
            docker run --rm -v "$APK_KEYSTORE_DIR":/keystore -e APK_KEYSTORE_PASSWORD -e APK_LEGACY_PASSWORD \
                "$APK_KEYTOOL_IMAGE" keytool "$@"
        }
        # Whether a password opens the key itself, not only the store (certreq needs the private key).
        opens() {
            keytool -certreq -alias "$APK_KEY_ALIAS" -keystore /keystore/release.keystore \
                -storepass:env "$1" -keypass:env "$1" > /dev/null 2>&1
        }
        copy() {
            docker run --rm -v "$APK_KEYSTORE_DIR":/keystore alpine:3.20 cp -p "/keystore/$1" "/keystore/$2"
        }

        if [ ! -s "$APK_KEYSTORE_FILE" ]; then
            echo "withPersistentApkKey: creating the signing key $APK_KEYSTORE_FILE"
            keytool -genkeypair -keyalg RSA -keysize 2048 -alias "$APK_KEY_ALIAS" -keystore /keystore/release.keystore \
                -storepass:env APK_KEYSTORE_PASSWORD -keypass:env APK_KEYSTORE_PASSWORD \
                -dname "$APK_KEY_DNAME" -validity 9999
            exit 0
        fi
        if opens APK_KEYSTORE_PASSWORD; then
            echo "withPersistentApkKey: the signing key is in place, the password fits"
            exit 0
        fi
        if [ -n "$APK_LEGACY_PASSWORD" ] && opens APK_LEGACY_PASSWORD; then
            echo "withPersistentApkKey: the key has its former password - re-encrypting it with credentials '$APK_PASSWORD_CREDENTIALS' (the same key)"
            copy release.keystore release.keystore.bak
            keytool -storepasswd -keystore /keystore/release.keystore \
                -storepass:env APK_LEGACY_PASSWORD -new:env APK_KEYSTORE_PASSWORD
            if opens APK_KEYSTORE_PASSWORD; then
                echo "withPersistentApkKey: done - the same key, the new password (the old copy: release.keystore.bak)"
                exit 0
            fi
            echo "withPersistentApkKey: the new password doesn't open the key - restoring the copy"
            copy release.keystore.bak release.keystore
            exit 1
        fi
        echo "withPersistentApkKey: the password (credentials '$APK_PASSWORD_CREDENTIALS') doesn't open $APK_KEYSTORE_FILE."
        echo "Fix the password - or delete the key to have a new one made (the app then has to be reinstalled on every phone):"
        echo "  docker run --rm -v $APK_KEYSTORE_DIR:/keystore alpine rm -f /keystore/release.keystore"
        exit 1
    '''
}
