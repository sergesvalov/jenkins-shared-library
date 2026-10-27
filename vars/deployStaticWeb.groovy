// Deploys static web assets (SPA, Telegram Mini App, HTML5 game, docs) to a remote server over SSH.
//
// By default, performs an atomic directory swap using a temporary staging folder so clients
// never receive partially uploaded files during an active deployment. Stale assets from previous
// builds are cleanly removed.
//
// Usage:
//   deployStaticWeb(
//       source:    'web/build/dist/webapp',
//       host:      env.PROD_SERVER_IP,
//       user:      env.DEPLOY_USER,
//       remoteDir: '/opt/bikes/web'
//   )
//
// Options:
//   source / sourceDir  local directory with static web files to deploy (required)
//   host                remote server IP or hostname (required)
//   user                remote SSH user (required)
//   remoteDir           destination directory on the remote server (required)
//   credentialsId       SSH credentials ID for sshagent (default: user)
//   atomic              true (default): upload to staging then swap; false: direct in-place scp
//   owner               chown user:group on destination (default: "${user}:${user}")
//   useSudo             true (default): use sudo for mkdir/chown; false: run without sudo
def call(Map args = [:]) {
    def source = args.source ?: args.sourceDir ?: error('deployStaticWeb: source (or sourceDir) is required')
    def host = args.host ?: error('deployStaticWeb: host is required')
    def user = args.user ?: error('deployStaticWeb: user is required')
    def remoteDir = args.remoteDir ?: error('deployStaticWeb: remoteDir is required')
    def credentialsId = args.credentialsId ?: user
    def atomic = args.containsKey('atomic') ? args.atomic : true
    def owner = args.owner ?: "${user}:${user}"
    def useSudo = args.containsKey('useSudo') ? args.useSudo : true

    // Normalize paths
    def cleanRemoteDir = remoteDir.replaceAll('/+$', '')
    def sudoPrefix = useSudo ? 'sudo ' : ''

    if (!fileExists(source)) {
        error("deployStaticWeb: source directory '${source}' does not exist")
    }

    echo "🚀 deployStaticWeb: deploying ${source} to ${user}@${host}:${cleanRemoteDir} (atomic: ${atomic})..."

    sshagent(credentials: [credentialsId]) {
        if (atomic) {
            def stagingDir = "${cleanRemoteDir}_staging_${env.BUILD_NUMBER ?: 'tmp'}"
            def backupDir  = "${cleanRemoteDir}_backup_${env.BUILD_NUMBER ?: 'tmp'}"
            sh """
                # 1. Prepare clean staging directory
                ssh -o StrictHostKeyChecking=no '${user}@${host}' "
                    ${sudoPrefix}rm -rf '${stagingDir}' '${backupDir}'
                    ${sudoPrefix}mkdir -p '${stagingDir}'
                    ${sudoPrefix}chown -R '${owner}' '${stagingDir}'
                "

                # 2. Upload files to staging
                scp -o StrictHostKeyChecking=no -r '${source}/.' '${user}@${host}:${stagingDir}/'

                # 3. Atomically swap staging into place
                ssh -o StrictHostKeyChecking=no '${user}@${host}' "
                    set -e
                    ${sudoPrefix}chown -R '${owner}' '${stagingDir}'
                    if [ -d '${cleanRemoteDir}' ]; then
                        ${sudoPrefix}mv '${cleanRemoteDir}' '${backupDir}'
                    fi
                    ${sudoPrefix}mv '${stagingDir}' '${cleanRemoteDir}'
                    if [ -d '${backupDir}' ]; then
                        ${sudoPrefix}rm -rf '${backupDir}'
                    fi
                "
            """
        } else {
            sh """
                ssh -o StrictHostKeyChecking=no '${user}@${host}' "
                    ${sudoPrefix}mkdir -p '${cleanRemoteDir}'
                    ${sudoPrefix}chown -R '${owner}' '${cleanRemoteDir}'
                "
                scp -o StrictHostKeyChecking=no -r '${source}/.' '${user}@${host}:${cleanRemoteDir}/'
                ssh -o StrictHostKeyChecking=no '${user}@${host}' "${sudoPrefix}chown -R '${owner}' '${cleanRemoteDir}'"
            """
        }
    }

    echo "✅ deployStaticWeb: successfully deployed to ${cleanRemoteDir}"
}