/**
 * Polls an HTTP endpoint until it answers 2xx/3xx (curl -f), with a timeout per request so a hung
 * service cannot hang the build. Fails the step after `retries` attempts.
 *
 *   checkHttpEndpoint(url: 'http://192.168.x.x:8000/api/health')
 *
 * Options: url (required), retries (default 3), sleepTime between attempts in seconds (default 5),
 * timeout per request in seconds (default 10). For containers + RestartCount use verifyDeployment.
 */
def call(Map params) {
    def url = params.url
    def retries = params.retries ?: 3
    def sleepTime = params.sleepTime ?: 5
    def timeout = params.timeout ?: 10

    echo "Checking HTTP endpoint: ${url}"
    retry(retries) {
        sleep sleepTime
        sh "curl -f -s -m ${timeout} -o /dev/null '${url}'"
    }
    echo "Endpoint is healthy!"
}
