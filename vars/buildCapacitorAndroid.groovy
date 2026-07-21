def call(Map config = [:]) {
    def buildScript = config.buildScript ?: 'npm run build:cap'
    def keystore = config.keystore ?: 'keystore/release.keystore'
    def storepass = config.storepass ?: 'password'
    def keyalias = config.keyalias ?: 'release'
    def keypass = config.keypass ?: 'password'

    withAndroidBuilder {
        // 1. Сборка веб-части для мобилки
        sh buildScript

        // 2. Инициализация платформы
        sh "npx cap add android || npx cap sync android"

        // AGP тянет свой aapt2 с Maven (только x86_64) независимо
        // от SDK build-tools — на arm64 падает с "Syntax error:
        // '(' unexpected" (shell пытается исполнить x86_64 ELF).
        // Официального arm64-билда от Google нет, поэтому
        // подсовываем проверенный по чек-сумме arm64 aapt2 из
        // Dockerfile.android (Commit451/android-arm-build-tools).
        sh 'echo "android.aapt2FromMavenOverride=/usr/local/bin/aapt2" >> android/gradle.properties'

        // 3. Компиляция через Gradle (с постоянным кэшем)
        sh '''
            cd android
            chmod +x gradlew
            ./gradlew assembleRelease --no-daemon --stacktrace --build-cache -Pandroid.useAndroidX=true
        '''

        signAndroidApk(
            unsignedApk: 'android/app/build/outputs/apk/release/app-release-unsigned.apk',
            signedApk:   'android/app/build/outputs/apk/release/app-release.apk',
            keystore:    keystore,
            storepass:   storepass,
            keyalias:    keyalias,
            keypass:     keypass
        )
    }
}
