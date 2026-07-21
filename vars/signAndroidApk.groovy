def call(Map args) {
    def unsignedApk = args.unsignedApk ?: error("Параметр unsignedApk обязателен для signAndroidApk")
    def signedApk   = args.signedApk ?: error("Параметр signedApk обязателен для signAndroidApk")
    def keystore    = args.keystore ?: error("Параметр keystore обязателен для signAndroidApk")
    def storepass   = args.storepass ?: error("Параметр storepass обязателен для signAndroidApk")
    def keyalias    = args.keyalias ?: error("Параметр keyalias обязателен для signAndroidApk")
    def keypass     = args.keypass ?: error("Параметр keypass обязателен для signAndroidApk")
    def buildTools  = args.buildTools ?: "35.0.0"
    def zipalign    = args.zipalign ?: "/usr/local/bin/zipalign"

    def alignedApk  = unsignedApk.replace('.apk', '-aligned.apk')

    sh """
        if [ -f "${unsignedApk}" ]; then
            echo "Выравнивание APK (zipalign)..."
            ${zipalign} -v -p 4 "${unsignedApk}" "${alignedApk}"
            
            echo "Подписание APK (apksigner)..."
            /opt/android-sdk/build-tools/${buildTools}/apksigner sign \\
                --ks "${keystore}" \\
                --ks-pass pass:${storepass} \\
                --ks-key-alias "${keyalias}" \\
                --key-pass pass:${keypass} \\
                --out "${signedApk}" "${alignedApk}"
            
            echo "Удаление временных файлов..."
            rm -f "${unsignedApk}" "${alignedApk}"
        else
            echo "Собранный unsigned APK не найден по пути ${unsignedApk}"
            exit 1
        fi
    """
}
