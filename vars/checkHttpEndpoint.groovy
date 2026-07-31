def call(Map params) {
    def url = params.url
    def retries = params.retries ?: 3
    def sleepTime = params.sleepTime ?: 5
    
    echo "Checking HTTP endpoint: ${url}"
    retry(retries) {
        sleep sleepTime
        sh "curl -f -s ${url}"
    }
    echo "Endpoint is healthy!"
}
