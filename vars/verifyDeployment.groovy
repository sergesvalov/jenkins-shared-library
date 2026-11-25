/**
 * Post-deploy check: "the deploy finished" is not "the service works". Over SSH it samples the
 * containers twice (running, not restarting, healthcheck not unhealthy — "starting" is waited for —
 * and RestartCount did not grow in between) and polls an HTTP endpoint with a timeout per request.
 * On a problem it prints the last log lines of the bad containers and marks the build UNSTABLE
 * (or fails it with failBuild: true).
 *
 *   verifyDeployment(
 *       host: env.DEPLOY_SERVER, user: env.SERVER_USER, credentialsId: env.SERVER_USER,
 *       containers: ['myapp-backend', 'myapp-frontend'],
 *       healthUrl: "http://${env.DEPLOY_SERVER}:8080/api/health"
 *   )
 *
 * Options:
 *   host, user          required when containers are given
 *   credentialsId       SSH key for sshagent, default = user
 *   containers          container names to check (may be empty: then only the HTTP check)
 *   healthUrl           optional HTTP endpoint; healthCodes — accepted codes, default [200]
 *   waitSeconds         how long to wait for the endpoint and "starting" healthchecks, default 90
 *   intervalSeconds     pause between the two container samples, default 15
 *   failBuild           default false (UNSTABLE)
 * Returns the list of problems (empty = healthy).
 */
def call(Map args) {
    List containers = args.containers ?: []
    def host = args.host
    def user = args.user
    def credentialsId = args.credentialsId ?: user
    def healthUrl = args.healthUrl
    List healthCodes = (args.healthCodes ?: [200]).collect { it.toString() }
    int waitSeconds = (args.waitSeconds ?: 90) as int
    int intervalSeconds = (args.intervalSeconds ?: 15) as int
    def failBuild = args.failBuild ?: false
    if (containers && (!host || !user)) {
        error('verifyDeployment: host and user are required to check containers')
    }

    def problems = []
    long deadline = System.currentTimeMillis() + waitSeconds * 1000L

    if (containers) {
        sshagent(credentials: [credentialsId]) {
            def first = sampleContainers(host, user, containers)
            sleep(time: intervalSeconds, unit: 'SECONDS')
            def second = sampleContainers(host, user, containers)
            while (true) {
                def starting = []
                for (String name : containers) {
                    if (second[name]?.health == 'starting') { starting << name }
                }
                if (!starting || System.currentTimeMillis() >= deadline) { break }
                echo "verifyDeployment: healthcheck starting: ${starting.join(', ')}"
                sleep(time: 5, unit: 'SECONDS')
                second = sampleContainers(host, user, containers)
            }
            def bad = []
            for (String name : containers) {
                def a = first[name]
                def b = second[name]
                def p = null
                if (b == null || b.status == 'missing') {
                    p = "${name}: container not found"
                } else if (b.restarting) {
                    p = "${name}: restarting (RestartCount ${b.restarts})"
                } else if (b.status != 'running') {
                    p = "${name}: ${b.status}"
                } else if (b.health == 'unhealthy') {
                    p = "${name}: healthcheck unhealthy"
                } else if (b.health == 'starting') {
                    p = "${name}: healthcheck still starting after ${waitSeconds}s"
                } else if (a != null && b.restarts > a.restarts) {
                    p = "${name}: restarted during the check (${a.restarts} -> ${b.restarts})"
                }
                if (p) { problems << p; bad << name }
            }
            for (String name : bad) {
                sh(returnStatus: true, script: "ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new ${user}@${host} 'docker logs --tail 40 ${name} 2>&1' || true")
            }
        }
    }

    if (healthUrl) {
        def code = ''
        while (true) {
            code = sh(returnStdout: true, script: "curl -s -o /dev/null -m 5 -w '%{http_code}' '${healthUrl}' || true").trim()
            if (healthCodes.contains(code) || System.currentTimeMillis() >= deadline) { break }
            sleep(time: 3, unit: 'SECONDS')
        }
        if (!healthCodes.contains(code)) {
            problems << "${healthUrl}: HTTP ${code ?: 'no answer'} (expected ${healthCodes.join('/')})"
        }
    }

    if (problems) {
        def msg = "verifyDeployment: " + problems.join('; ')
        if (failBuild) { error(msg) }
        unstable(msg)
    } else {
        echo "verifyDeployment: OK (${containers.size()} containers${healthUrl ? ', ' + healthUrl : ''})"
    }
    return problems
}

/** name -> [status, restarting, restarts, health]; a missing container comes back as status 'missing'. */
def sampleContainers(String host, String user, List containers) {
    def fmt = '{{.Name}} {{.State.Status}} {{.State.Restarting}} {{.RestartCount}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}'
    def parts = []
    for (String c : containers) {
        parts << "docker inspect -f '${fmt}' ${c} 2>/dev/null || echo '/${c} missing false 0 none'"
    }
    def out = sh(returnStdout: true,
        script: "ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new ${user}@${host} \"${parts.join('; ')}\"").trim()
    def result = [:]
    for (String line : out.readLines()) {
        def f = line.trim().split(/\s+/)
        if (f.length < 5) { continue }
        result[f[0].replaceFirst('^/', '')] = [
            status: f[1], restarting: f[2] == 'true', restarts: (f[3] as int), health: f[4],
        ]
    }
    return result
}
