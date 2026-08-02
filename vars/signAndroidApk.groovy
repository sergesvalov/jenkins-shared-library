def call(Map args) {
    def unsignedApk = args.unsignedApk ?: error("Parameter unsignedApk is required for signAndroidApk")
    def signedApk   = args.signedApk ?: error("Parameter signedApk is required for signAndroidApk")
    def keystore    = args.keystore ?: error("Parameter keystore is required for signAndroidApk")
    def storepass   = args.storepass ?: error("Parameter storepass is required for signAndroidApk")
    def keyalias    = args.keyalias ?: error("Parameter keyalias is required for signAndroidApk")
    def keypass     = args.keypass ?: error("Parameter keypass is required for signAndroidApk")
    def buildTools  = args.buildTools ?: "35.0.0"
    def zipalign    = args.zipalign ?: "/usr/local/bin/zipalign"

    def alignedApk  = unsignedApk.replace('.apk', '-aligned.apk')

    sh """
        if [ -f "${unsignedApk}" ]; then
            echo "Aligning APK (zipalign)..."
            ${zipalign} -v -p 4 "${unsignedApk}" "${alignedApk}"
            
            echo "Signing APK (apksigner)..."
            /opt/android-sdk/build-tools/${buildTools}/apksigner sign \\
                --ks "${keystore}" \\
                --ks-pass pass:${storepass} \\
                --ks-key-alias "${keyalias}" \\
                --key-pass pass:${keypass} \\
                --out "${signedApk}" "${alignedApk}"
            
            echo "Removing temporary files..."
            rm -f "${unsignedApk}" "${alignedApk}"
        else
            echo "Built unsigned APK not found at ${unsignedApk}"
            exit 1
        fi
    """
}
