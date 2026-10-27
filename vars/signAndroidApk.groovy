// Aligns (zipalign) and signs (apksigner) an Android APK.
//
// By default, retrieves the release keystore, passwords, and key alias directly from OpenBao
// (backed by Jenkins credentials: apk-keystore-base64, apk-sign-key, apk-key-alias).
//
// Usage:
//   // 1. In-place signing with common OpenBao key:
//   signAndroidApk(apk: 'android/build/outputs/apk/release/myapp.apk')
//   // or shorthand:
//   signAndroidApk('android/build/outputs/apk/release/myapp.apk')
//
//   // 2. Allow unsigned build if OpenBao is unreachable:
//   signAndroidApk(apk: 'android/build/outputs/apk/release/myapp.apk', required: false)
//
//   // 3. Separate unsigned and signed APKs:
//   signAndroidApk(
//       unsignedApk: 'android/app/build/outputs/apk/release/app-release-unsigned.apk',
//       signedApk:   'android/app/build/outputs/apk/release/myapp.apk'
//   )
//
//   // 4. Inside a Docker image (e.g. if the host agent lacks apksigner):
//   signAndroidApk(apk: 'app.apk', dockerImage: env.BUILDER_IMAGE)
//
// Options (all optional except apk / unsignedApk):
//   apk                     path of the APK to sign in-place (or input APK)
//   unsignedApk             path of the input unsigned APK (alternative to apk)
//   signedApk               destination path for the signed APK (default: same as apk)
//   required                true (default): fail if OpenBao credentials missing; false: warn & skip
//   cleanUnsigned           true: delete unsignedApk after signing (default: true if unsignedApk != signedApk)
//   keystore                explicit path to keystore (default: from OpenBao)
//   storepass               explicit keystore password (default: from OpenBao)
//   keyalias                explicit key alias (default: from OpenBao, fallback 'release')
//   keypass                 explicit key password (default: storepass)
//   keystoreCredentialsId   OpenBao base64 keystore credential (default 'apk-keystore-base64')
//   passwordCredentialsId   OpenBao password credential (default 'apk-sign-key')
//   aliasCredentialsId      OpenBao key alias credential (default 'apk-key-alias')
//   dockerImage             Docker image with Android SDK/apksigner to run within (optional)
//   dockerArgs              extra arguments for docker.image().inside() (default '-u root')
//   zipalign                explicit path to zipalign binary (default: auto-detected)
//   apksigner               explicit path to apksigner binary (default: auto-detected)

def call(String apkPath) {
    call(apk: apkPath)
}

def call(Map args = [:]) {
    def inputApk = args.apk ?: args.unsignedApk
    if (!inputApk) {
        error("signAndroidApk: parameter 'apk' (or 'unsignedApk') is required")
    }
    def outputApk = args.signedApk ?: args.apk ?: inputApk.replace('-unsigned.apk', '.apk')
    def cleanUnsigned = args.containsKey('cleanUnsigned') ? args.cleanUnsigned : (args.containsKey('unsignedApk') && inputApk != outputApk)
    def required = args.containsKey('required') ? args.required : true

    def mergedArgs = [:]
    mergedArgs.putAll(args)
    mergedArgs.apk = inputApk
    mergedArgs.signedApk = outputApk
    mergedArgs.cleanUnsigned = cleanUnsigned
    mergedArgs.required = required

    if (args.dockerImage) {
        def dArgs = args.dockerArgs ?: '-u root'
        mergedArgs.remove('dockerImage')
        docker.image(args.dockerImage).inside(dArgs) {
            executeSign(mergedArgs)
        }
        return
    }

    executeSign(mergedArgs)
}

private void executeSign(Map args) {
    def inputApk = args.apk
    def outputApk = args.signedApk
    def cleanUnsigned = args.cleanUnsigned
    def required = args.required

    // Explicit keystore overrides:
    def explicitKeystore = args.keystore
    def explicitPassword = args.storepass
    def explicitAlias    = args.keyalias
    def explicitKeypass  = args.keypass

    boolean hasExplicit = (explicitKeystore != null && explicitPassword != null)
    boolean alreadyInKeyContext = (env.APK_SIGNING == 'true' && env.APK_KEYSTORE_FILE)

    if (hasExplicit || alreadyInKeyContext) {
        doSignStep(
            inputApk: inputApk,
            outputApk: outputApk,
            keystore: explicitKeystore ?: env.APK_KEYSTORE_FILE,
            storepass: explicitPassword ?: env.APK_KEYSTORE_PASSWORD,
            keyalias: explicitAlias ?: env.APK_KEY_ALIAS ?: 'release',
            keypass: explicitKeypass ?: explicitPassword ?: env.APK_KEYSTORE_PASSWORD,
            zipalign: args.zipalign,
            apksigner: args.apksigner,
            cleanUnsigned: cleanUnsigned
        )
    } else if (env.APK_SIGNING == 'false') {
        echo "[WARN] signAndroidApk: APK signing skipped because signing key is not available in environment."
    } else {
        // Retrieve key and credentials from OpenBao
        withApkSigningKey(
            keystoreCredentialsId: args.keystoreCredentialsId ?: 'apk-keystore-base64',
            passwordCredentialsId: args.passwordCredentialsId ?: 'apk-sign-key',
            aliasCredentialsId:    args.aliasCredentialsId ?: 'apk-key-alias',
            required: required
        ) {
            if (env.APK_SIGNING == 'false') {
                echo "[WARN] signAndroidApk: OpenBao signing credentials not available. Skipping APK signing."
                return
            }
            doSignStep(
                inputApk: inputApk,
                outputApk: outputApk,
                keystore: explicitKeystore ?: env.APK_KEYSTORE_FILE,
                storepass: explicitPassword ?: env.APK_KEYSTORE_PASSWORD,
                keyalias: explicitAlias ?: env.APK_KEY_ALIAS ?: 'release',
                keypass: explicitKeypass ?: explicitPassword ?: env.APK_KEYSTORE_PASSWORD,
                zipalign: args.zipalign,
                apksigner: args.apksigner,
                cleanUnsigned: cleanUnsigned
            )
        }
    }
}

private void doSignStep(Map config) {
    def inputApk = config.inputApk
    def outputApk = config.outputApk
    def keystore = config.keystore
    def storepass = config.storepass
    def keyalias = config.keyalias
    def keypass = config.keypass ?: storepass
    def zipalignArg = config.zipalign ?: ''
    def apksignerArg = config.apksigner ?: ''
    def cleanUnsigned = config.cleanUnsigned

    if (!keystore || !fileExists(keystore)) {
        error("signAndroidApk: Keystore file not found at '${keystore}'")
    }

    if (!fileExists(inputApk)) {
        error("signAndroidApk: Input APK not found at '${inputApk}'")
    }

    withEnv([
        "SIGN_STOREPASS=${storepass}",
        "SIGN_KEYPASS=${keypass}",
        "INPUT_APK=${inputApk}",
        "OUTPUT_APK=${outputApk}",
        "KEYSTORE_FILE=${keystore}",
        "KEY_ALIAS=${keyalias}",
        "ZIPALIGN_PATH=${zipalignArg}",
        "APKSIGNER_PATH=${apksignerArg}",
        "CLEAN_UNSIGNED=${cleanUnsigned ? 'true' : 'false'}"
    ]) {
        sh '''
            set -e

            find_tool() {
                local explicit="$1"
                local tool="$2"
                if [ -n "$explicit" ] && [ -x "$explicit" ]; then
                    echo "$explicit"
                    return 0
                fi
                if command -v "$tool" >/dev/null 2>&1; then
                    command -v "$tool"
                    return 0
                fi
                if [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME/build-tools" ]; then
                    local found
                    found="$(find "$ANDROID_HOME/build-tools" -name "$tool" -type f 2>/dev/null | sort -V | tail -n 1)"
                    if [ -n "$found" ] && [ -x "$found" ]; then
                        echo "$found"
                        return 0
                    fi
                fi
                if [ -d "/opt/android-sdk/build-tools" ]; then
                    local found
                    found="$(find "/opt/android-sdk/build-tools" -name "$tool" -type f 2>/dev/null | sort -V | tail -n 1)"
                    if [ -n "$found" ] && [ -x "$found" ]; then
                        echo "$found"
                        return 0
                    fi
                fi
                for p in "/usr/bin/$tool" "/usr/local/bin/$tool"; do
                    if [ -x "$p" ]; then
                        echo "$p"
                        return 0
                    fi
                done
                return 1
            }

            ZIPALIGN_BIN="$(find_tool "$ZIPALIGN_PATH" "zipalign" || true)"
            APKSIGNER_BIN="$(find_tool "$APKSIGNER_PATH" "apksigner" || true)"

            if [ -z "$APKSIGNER_BIN" ]; then
                echo "ERROR: apksigner not found in PATH or Android SDK build-tools!"
                exit 1
            fi

            ALIGNED_APK="$INPUT_APK"
            TMP_ALIGNED=""

            if [ -n "$ZIPALIGN_BIN" ]; then
                if "$ZIPALIGN_BIN" -c 4 "$INPUT_APK" >/dev/null 2>&1; then
                    echo "signAndroidApk: APK is already 4-byte aligned."
                else
                    echo "signAndroidApk: Aligning APK with $ZIPALIGN_BIN..."
                    TMP_ALIGNED="${INPUT_APK}.aligned.$$"
                    "$ZIPALIGN_BIN" -f -p 4 "$INPUT_APK" "$TMP_ALIGNED"
                    ALIGNED_APK="$TMP_ALIGNED"
                fi
            else
                echo "signAndroidApk: [WARN] zipalign not found, proceeding directly with apksigner..."
            fi

            echo "signAndroidApk: Signing APK with $APKSIGNER_BIN (alias: $KEY_ALIAS)..."
            TMP_OUT="${OUTPUT_APK}.signed.$$"

            "$APKSIGNER_BIN" sign \
                --ks "$KEYSTORE_FILE" \
                --ks-pass env:SIGN_STOREPASS \
                --ks-key-alias "$KEY_ALIAS" \
                --key-pass env:SIGN_KEYPASS \
                --out "$TMP_OUT" \
                "$ALIGNED_APK"

            mkdir -p "$(dirname "$OUTPUT_APK")"
            mv -f "$TMP_OUT" "$OUTPUT_APK"
            [ -n "$TMP_ALIGNED" ] && [ -f "$TMP_ALIGNED" ] && rm -f "$TMP_ALIGNED" || true

            if [ "$INPUT_APK" != "$OUTPUT_APK" ] && [ "$CLEAN_UNSIGNED" = "true" ]; then
                rm -f "$INPUT_APK" || true
            fi

            echo "signAndroidApk: Verifying APK signature..."
            "$APKSIGNER_BIN" verify --verbose "$OUTPUT_APK"
            echo "signAndroidApk: APK successfully signed and verified -> $OUTPUT_APK"
        '''
    }
}