// Publishes a built APK for an app's self-update: writes latest.json next to it (versionName,
// versionCode = the build number, size, sha256, build time, commit) and copies both to a directory
// on a server over SSH. They are copied under temporary names and then renamed, so an app never
// sees a half-copied APK. The app's backend serves that directory; the app compares its build with
// latest.json and offers the update.
//
// No hosts or users are kept here: the project passes them.
//
// Usage:
//   publishApkForUpdate(
//       apk: 'android/app/build/outputs/apk/release/myapp.apk',
//       versionName: '1.4',
//       host: env.PROD_SERVER_IP, user: env.DEPLOY_USER, credentialsId: env.DEPLOY_USER,
//       remoteDir: '/opt/myapp/downloads'
//   )
//
// Options (all required but versionName):
//   apk            path of the signed APK in the workspace
//   versionName    shown to the user (default: read from android/app/build.gradle.kts)
//   host, user     where to copy it over SSH
//   credentialsId  SSH key credentials for sshagent
//   remoteDir      the directory on the server (created when missing, owned by `user`)
def call(Map args = [:]) {
    def apk = args.apk ?: error('publishApkForUpdate: apk is required')
    def host = args.host ?: error('publishApkForUpdate: host is required')
    def user = args.user ?: error('publishApkForUpdate: user is required')
    def credentialsId = args.credentialsId ?: error('publishApkForUpdate: credentialsId is required')
    def remoteDir = args.remoteDir ?: error('publishApkForUpdate: remoteDir is required')
    def versionName = args.versionName ?: sh(returnStdout: true,
        script: "grep -oE 'versionName = \"[^\"]+\"' android/app/build.gradle.kts | cut -d'\"' -f2").trim()

    def apkDir = apk.contains('/') ? apk.substring(0, apk.lastIndexOf('/')) : '.'
    def apkName = apk.substring(apk.lastIndexOf('/') + 1)
    sh """
        cd '${apkDir}'
        SIZE=\$(stat -c %s '${apkName}')
        SHA=\$(sha256sum '${apkName}' | cut -c1-64)
        printf '{"versionName":"%s","versionCode":%s,"size":%s,"sha256":"%s","builtAt":"%s","commit":"%s"}\n' \
          '${versionName}' '${env.BUILD_NUMBER}' "\$SIZE" "\$SHA" "\$(date -u +%Y-%m-%dT%H:%M:%SZ)" "\$(git rev-parse --short HEAD)" > latest.json
        cat latest.json
    """
    sshagent(credentials: [credentialsId]) {
        sh """
            ssh -o StrictHostKeyChecking=no '${user}@${host}' "sudo mkdir -p '${remoteDir}' && sudo chown '${user}:' '${remoteDir}'"
            scp -o StrictHostKeyChecking=no '${apkDir}/${apkName}' '${user}@${host}:${remoteDir}/${apkName}.new'
            scp -o StrictHostKeyChecking=no '${apkDir}/latest.json' '${user}@${host}:${remoteDir}/latest.json.new'
            ssh -o StrictHostKeyChecking=no '${user}@${host}' "cd '${remoteDir}' && mv -f '${apkName}.new' '${apkName}' && mv -f latest.json.new latest.json"
        """
    }
    echo "publishApkForUpdate: ${apkName} of build ${env.BUILD_NUMBER} (${versionName}) is out for self-update"
}
