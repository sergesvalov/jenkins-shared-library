/**
 * pg_dump (custom format) right before a deploy that may migrate the schema. The nightly backup can be
 * a day old; this one is taken seconds before the change. Keeps the last N dumps per database.
 *
 *   backupPostgres(host: env.DB_SERVER, user: env.SERVER_USER, database: 'myapp')                // PostgreSQL on the host
 *   backupPostgres(host: env.DEPLOY_SERVER, user: env.SERVER_USER, database: 'myapp', container: 'myapp_db')  // in a container
 *
 * Options:
 *   host, user, database   required
 *   credentialsId          SSH key for sshagent, default = user
 *   container              dump inside this container (docker exec, as POSTGRES_USER); default: host PostgreSQL
 *                          via `sudo -u postgres` (user needs passwordless sudo for that)
 *   dir                    where dumps go on that host, default /var/backups/predeploy/<database>
 *   keep                   how many dumps to keep, default 10
 * Fails the build when the dump fails: deploying a migration without a fresh backup is the risk this step removes.
 * Returns the dump path on the host.
 */
def call(Map args) {
    def host = args.host ?: error('backupPostgres: host is required')
    def user = args.user ?: error('backupPostgres: user is required')
    def database = args.database ?: error('backupPostgres: database is required')
    def container = args.container ?: ''
    if (!(database ==~ /[A-Za-z0-9_.-]+/) || !(container ==~ /[A-Za-z0-9_.-]*/)) {
        error("backupPostgres: bad database or container name")
    }
    def credentialsId = args.credentialsId ?: user
    def dir = args.dir ?: "/var/backups/predeploy/${database}"
    int keep = (args.keep ?: 10) as int

    // In the container the superuser is POSTGRES_USER from its own environment (expanded there, not here).
    def dump = container
        ? "docker exec ${container} sh -c 'pg_dump -U \"\${POSTGRES_USER:-postgres}\" -Fc ${database}'"
        : "sudo -n -u postgres pg_dump -Fc ${database}"
    // The script goes to the host on stdin through a quoted heredoc: no local expansion, one level of quoting.
    def remote = """set -e
sudo -n mkdir -p '${dir}'
sudo -n chown '${user}:' '${dir}'
F="${dir}/${database}_${env.BUILD_NUMBER}_\$(date +%Y%m%d_%H%M%S).dump"
${dump} > "\$F"
test -s "\$F"
ls -1t '${dir}'/${database}_*.dump | tail -n +${keep + 1} | xargs -r rm -f
echo "\$F"
"""
    def path = ''
    sshagent(credentials: [credentialsId]) {
        path = sh(returnStdout: true,
            script: "ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new ${user}@${host} 'bash -s' <<'BACKUP_PG_EOF'\n${remote}BACKUP_PG_EOF\n"
        ).trim().readLines().last()
    }
    echo "backupPostgres: ${database} -> ${host}:${path}"
    return path
}
