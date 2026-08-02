def call(Map config = [:]) {
    def buildScript = config.buildScript ?: 'npm run build:cap'
    def keystore = config.keystore ?: 'keystore/release.keystore'
    def storepass = config.storepass ?: 'password'
    def keyalias = config.keyalias ?: 'release'
    def keypass = config.keypass ?: 'password'
    def generateAssets = config.generateAssets ?: false

    withAndroidBuilder {
        // 1. Build web part for mobile
        sh buildScript

        // 2. Initialize platform
        sh "npx cap add android || npx cap sync android"
        
        if (generateAssets) {
            sh "npx @capacitor/assets generate --android"
        }

        // AGP pulls its own aapt2 from Maven (x86_64 only) independent
        // of SDK build-tools — on arm64 it fails with "Syntax error:
        // '(' unexpected" (shell tries to execute x86_64 ELF).
        // There is no official arm64 build from Google, so we
        // substitute it with a checksum-verified arm64 aapt2 from
        // Dockerfile.android (Commit451/android-arm-build-tools).
        sh 'echo "android.aapt2FromMavenOverride=/usr/local/bin/aapt2" >> android/gradle.properties'

        // 3. Compile via Gradle (with persistent cache)
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
