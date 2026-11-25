/**
 * Fails early when a secrets file the deploy relies on is missing or empty on the target host.
 * Without it docker creates an empty directory in place of the bind-mounted file and the service
 * starts broken; this stops the build before the deploy with a hint what to do.
 *
 *   checkSecretsFile(host: env.DEPLOY_SERVER, user: env.SERVER_USER, path: '/opt/myapp/secrets/.env',
 *                    hint: 'kv/apps/myapp in OpenBao + homeservices deploy --tags myapp; logs: docker logs baoagent-myapp')
 *
 * Options: host, user, path (required); credentialsId (default = user); hint (printed on failure);
 * sudo (default true: the file is usually root-only).
 */
def call(Map args) {
    def host = args.host ?: error('checkSecretsFile: host is required')
    def user = args.user ?: error('checkSecretsFile: user is required')
    def path = args.path ?: error('checkSecretsFile: path is required')
    def credentialsId = args.credentialsId ?: user
    def sudo = args.containsKey('sudo') ? args.sudo : true
    def test = "${sudo ? 'sudo -n ' : ''}test -s '${path}'"
    def rc = 0
    sshagent(credentials: [credentialsId]) {
        rc = sh(returnStatus: true, script: "ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new ${user}@${host} \"${test}\"")
    }
    if (rc != 0) {
        error("checkSecretsFile: ${host}:${path} is missing or empty — secrets were not rendered." +
              (args.hint ? " ${args.hint}" : ''))
    }
    echo "checkSecretsFile: ${host}:${path} present"
}
